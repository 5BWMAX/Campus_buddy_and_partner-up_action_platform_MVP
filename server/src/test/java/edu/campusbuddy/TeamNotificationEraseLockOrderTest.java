package edu.campusbuddy;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Import(TeamNotificationEraseLockOrderTest.HookConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TeamNotificationEraseLockOrderTest extends PgIntegrationSupport {
  private static final int FUTURE_TIMEOUT_SECONDS = 20;
  private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(4);

  @Autowired TeamNotificationEraseProbe probe;

  @BeforeEach
  void verifyIsolatedDatabase() throws Exception {
    String url = System.getenv("TEST_DB_URL");
    assertNotNull(url);
    assertTrue(
        url.matches(
            "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):5432/campus_buddy_mvp2_test(?:\\?.*)?"));
    assertEquals(
        "campus_buddy_mvp2_test", jdbc.queryForObject("select current_database()", String.class));
    String serverAddressWithMask =
        jdbc.queryForObject("select inet_server_addr()::text", String.class);
    assertNotNull(serverAddressWithMask);
    String serverAddress = serverAddressWithMask.split("/", 2)[0];
    assertTrue(InetAddress.getByName(serverAddress).isLoopbackAddress(), serverAddress);
    assertEquals(5432, jdbc.queryForObject("select inet_server_port()", Integer.class));
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void eraseFirstApplyWaitsBeforeRequestingTheActivityLock() throws Exception {
    verifyScenario(Mode.ERASE_FIRST, Role.APPLY);
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void eraseFirstExitWaitsBeforeRequestingTheActivityLock() throws Exception {
    verifyScenario(Mode.ERASE_FIRST, Role.EXIT);
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void eraseFirstWithdrawalWaitsBeforeRequestingTheActivityLock() throws Exception {
    verifyScenario(Mode.ERASE_FIRST, Role.WITHDRAW);
  }

  @Test
  @org.junit.jupiter.api.Order(4)
  void businessFirstApplyBlocksEraseAtTheOwnerUserLock() throws Exception {
    verifyScenario(Mode.BUSINESS_FIRST, Role.APPLY);
  }

  @Test
  @org.junit.jupiter.api.Order(5)
  void businessFirstExitBlocksEraseAtTheOwnerUserLock() throws Exception {
    verifyScenario(Mode.BUSINESS_FIRST, Role.EXIT);
  }

  @Test
  @org.junit.jupiter.api.Order(6)
  void businessFirstWithdrawalBlocksEraseAtTheOwnerUserLock() throws Exception {
    verifyScenario(Mode.BUSINESS_FIRST, Role.WITHDRAW);
  }

  private void verifyScenario(Mode mode, Role role) throws Exception {
    Scenario scenario = scenario(role);
    Race race =
        mode == Mode.ERASE_FIRST
            ? runEraseFirst(role, businessAction(role, scenario))
            : runBusinessFirst(role, businessAction(role, scenario));

    assertCommitted(race.owner(), race.business());
    assertNoDeadlockOrRetry(race);
    assertEquals("DELETED", race.owner().value().text("status"));
    assertBusinessState(role, scenario, race.business().value());
    assertOwnerErased(scenario);
    assertExpectedSideEffects(role, scenario.sideEffects());
    // V3 erasure removes the owner's pre-existing delivery records. Only a notification
    // created by a business transaction after erasure can remain for that recipient.
    long ownerRecords = mode == Mode.ERASE_FIRST ? 1 : 0;
    assertEquals(ownerRecords, sql.count("select count(*) from notification where recipient_id=?", ALICE));
    assertEquals(ownerRecords, sql.count("select count(*) from delivery_task where recipient_id=?", ALICE));
    assertEquals(0, sql.count("select count(*) from idempotency_record where actor_id=?", ALICE));
    printEvidence(race);
  }

  private Scenario scenario(Role role) {
    List<UUID> created =
        List.of(
            activity(ALICE, 3, 7200, 10800),
            activity(ALICE, 3, 14400, 18000),
            activity(ALICE, 3, 21600, 25200));
    List<UUID> ordered = ownerActivityIds();
    assertEquals(3, ordered.size());
    assertEquals(new HashSet<>(created), new HashSet<>(ordered));
    UUID target = ordered.get(1);

    Row application = null;
    if (role == Role.EXIT) approve(ALICE, target, apply(BOB, target));
    else if (role == Role.WITHDRAW) application = apply(BOB, target);

    assertEquals(3, exactLocationCount());
    Map<UUID, String> statuses = new LinkedHashMap<>();
    for (UUID activity : ordered)
      statuses.put(
          activity,
          jdbc.queryForObject(
              "select status from activity where id=?", String.class, activity));
    return new Scenario(
        target, ordered, statuses, application, UUID.randomUUID(), sideEffects());
  }

  private Supplier<Row> businessAction(Role role, Scenario scenario) {
    return switch (role) {
      case APPLY ->
          () ->
              team.apply(
                  BOB,
                  scenario.target(),
                  Row.of("note", "A3-E apply versus owner erase"),
                  scenario.idempotencyKey());
      case EXIT ->
          () ->
              team.exit(
                  BOB,
                  scenario.target(),
                  Row.of("reason", "A3-E exit versus owner erase"),
                  scenario.idempotencyKey());
      case WITHDRAW ->
          () -> {
            Row application = Objects.requireNonNull(scenario.application());
            return team.resolve(
                BOB,
                scenario.target(),
                application.id("id"),
                Row.of(
                    "round",
                    application.num("round"),
                    "expectedVersion",
                    application.num("version")),
                true);
          };
      case OWNER_ERASE -> throw new IllegalArgumentException("not a business role");
    };
  }

  private Race runEraseFirst(Role businessRole, Supplier<Row> businessAction) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Outcome> ownerFuture = null, businessFuture = null;
    probe.prepare(Mode.ERASE_FIRST, businessRole);
    try {
      ownerFuture =
          pool.submit(() -> outcome(() -> probe.call(Role.OWNER_ERASE, () -> identity.erase(ALICE))));
      await(
          probe.ownerActivitiesLocked,
          "owner erase to lock and scrub every owner activity");

      businessFuture =
          pool.submit(() -> outcome(() -> probe.call(businessRole, businessAction)));
      await(
          probe.businessReferenceRequested,
          businessRole + " transaction to request owner KEY SHARE");
      assertIndependentWorkers();

      awaitBlockedBy(probe.businessPid.get(), probe.ownerPid.get());
      List<Integer> businessBlockers = blockers(probe.businessPid.get());
      assertOnlyBlockedBy(businessBlockers, probe.ownerPid.get());
      assertEquals(
          1,
          probe.businessUsersLocked.getCount(),
          "business transaction must still be inside the mixed user lock call");
      assertEquals(
          1,
          probe.businessActivityLocked.getCount(),
          "business transaction must not request activity FOR UPDATE while waiting for owner");
      assertNoBlockers(probe.ownerPid.get());
      assertFalse(isBlockedBy(probe.ownerPid.get(), probe.businessPid.get()));
      probe.allowOwnerCompletion.countDown();

      Outcome owner = ownerFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      Outcome business = businessFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertEquals(0, probe.businessUsersLocked.getCount());
      assertEquals(0, probe.businessActivityLocked.getCount());
      Map<Integer, CleanupObservation> cleanup = observeCleanup(probe.allPids());
      return new Race(
          Mode.ERASE_FIRST,
          businessRole,
          business,
          owner,
          probe.businessPid.get(),
          probe.ownerPid.get(),
          businessBlockers,
          List.of(),
          Set.copyOf(probe.observedSqlStates),
          probe.attempts(businessRole),
          probe.attempts(Role.OWNER_ERASE),
          cleanup);
    } finally {
      probe.releaseAll();
      cancelIfRunning(businessFuture);
      cancelIfRunning(ownerFuture);
      try {
        closePool(pool);
      } finally {
        probe.disable();
      }
    }
  }

  private Race runBusinessFirst(Role businessRole, Supplier<Row> businessAction) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Outcome> businessFuture = null, ownerFuture = null;
    probe.prepare(Mode.BUSINESS_FIRST, businessRole);
    try {
      businessFuture =
          pool.submit(() -> outcome(() -> probe.call(businessRole, businessAction)));
      await(
          probe.businessActivityLocked,
          businessRole + " transaction to acquire ordered user and target activity locks");
      assertEquals(0, probe.businessUsersLocked.getCount());

      ownerFuture =
          pool.submit(() -> outcome(() -> probe.call(Role.OWNER_ERASE, () -> identity.erase(ALICE))));
      await(probe.ownerUserUpdateRequested, "owner erase to request owner FOR UPDATE");
      assertIndependentWorkers();

      awaitBlockedBy(probe.ownerPid.get(), probe.businessPid.get());
      List<Integer> ownerBlockers = blockers(probe.ownerPid.get());
      assertOnlyBlockedBy(ownerBlockers, probe.businessPid.get());
      assertEquals(
          1,
          probe.ownerUserLocked.getCount(),
          "owner erase must still be inside user FOR UPDATE");
      assertEquals(
          1,
          probe.ownerActivitiesLocked.getCount(),
          "owner erase must not request any activity lock while waiting for owner user");
      assertNoBlockers(probe.businessPid.get());
      assertFalse(isBlockedBy(probe.businessPid.get(), probe.ownerPid.get()));
      probe.allowBusinessCompletion.countDown();

      Outcome business = businessFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      Outcome owner = ownerFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertEquals(0, probe.ownerUserLocked.getCount());
      assertEquals(0, probe.ownerActivitiesLocked.getCount());
      Map<Integer, CleanupObservation> cleanup = observeCleanup(probe.allPids());
      return new Race(
          Mode.BUSINESS_FIRST,
          businessRole,
          business,
          owner,
          probe.businessPid.get(),
          probe.ownerPid.get(),
          List.of(),
          ownerBlockers,
          Set.copyOf(probe.observedSqlStates),
          probe.attempts(businessRole),
          probe.attempts(Role.OWNER_ERASE),
          cleanup);
    } finally {
      probe.releaseAll();
      cancelIfRunning(ownerFuture);
      cancelIfRunning(businessFuture);
      try {
        closePool(pool);
      } finally {
        probe.disable();
      }
    }
  }

  private void assertBusinessState(Role role, Scenario scenario, Row result) {
    switch (role) {
      case APPLY -> {
        assertEquals("PENDING", result.text("status"));
        assertEquals("PENDING", application(scenario.target()).text("status"));
        assertIdempotencyRecord(
            BOB, "apply:" + scenario.target(), scenario.idempotencyKey());
      }
      case EXIT -> {
        assertEquals("LEFT", result.text("status"));
        assertEquals("LEFT", part(scenario.target(), BOB).text("status"));
        assertIdempotencyRecord(
            BOB, "exit:" + scenario.target(), scenario.idempotencyKey());
      }
      case WITHDRAW -> {
        assertEquals("WITHDRAWN", result.text("status"));
        assertEquals("WITHDRAWN", application(scenario.target()).text("status"));
      }
      case OWNER_ERASE -> fail("not a business role");
    }
  }

  private void assertOwnerErased(Scenario scenario) {
    Row owner = sql.must("select * from user_account where id=?", ALICE);
    assertEquals("DELETED", owner.text("status"));
    assertFalse(owner.bool("verified"));
    Row privacy = sql.must("select * from privacy_setting where user_id=?", ALICE);
    assertEquals("HIDDEN", privacy.text("contactVisibility"));
    assertEquals("HIDDEN", privacy.text("locationVisibility"));
    assertEquals(1, sql.count("select count(*) from erasure_log where user_id=?", ALICE));
    assertEquals(scenario.ownerActivities(), ownerActivityIds());
    assertEquals(0, exactLocationCount());
    scenario.activityStatuses().forEach(
        (activity, status) ->
            assertEquals(
                status,
                jdbc.queryForObject(
                    "select status from activity where id=?", String.class, activity)));
  }

  private void assertExpectedSideEffects(Role role, SideEffects before) {
    SideEffects after = sideEffects();
    assertEquals(1, after.notifications() - before.notifications());
    assertEquals(1, after.deliveryTasks() - before.deliveryTasks());
    assertEquals(1, after.readyDeliveryTasks() - before.readyDeliveryTasks());
    assertEquals(role == Role.WITHDRAW ? 0 : 1, after.idempotencyRecords() - before.idempotencyRecords());
  }

  private Row application(UUID activity) {
    return sql.must("select * from application where activity_id=? and user_id=?", activity, BOB);
  }

  private List<UUID> ownerActivityIds() {
    return jdbc.query(
        "select id from activity where owner_id=? order by id",
        (rs, row) -> rs.getObject(1, UUID.class),
        ALICE);
  }

  private long exactLocationCount() {
    return sql.count(
        "select count(*) from activity where owner_id=? and exact_location_ciphertext is not null",
        ALICE);
  }

  private SideEffects sideEffects() {
    return new SideEffects(
        sql.count("select count(*) from notification where recipient_id<>?", ALICE),
        sql.count("select count(*) from delivery_task where recipient_id<>?", ALICE),
        sql.count("select count(*) from delivery_task where status='READY' and recipient_id<>?", ALICE),
        sql.count("select count(*) from idempotency_record where actor_id<>?", ALICE));
  }

  private void assertIdempotencyRecord(UUID actor, String operation, UUID key) {
    assertEquals(
        1,
        sql.count(
            "select count(*) from idempotency_record where actor_id=? and operation=? and key=?",
            actor,
            operation,
            key));
  }

  private void assertCommitted(Outcome... outcomes) {
    for (Outcome outcome : outcomes) assertTrue(outcome.succeeded(), outcome.description());
  }

  private void assertNoDeadlockOrRetry(Race race) {
    assertFalse(race.business().hasSqlState("40P01"), race.business().description());
    assertFalse(race.owner().hasSqlState("40P01"), race.owner().description());
    assertEquals(0, probe.deadlockEvents.get());
    assertFalse(race.sqlStates().contains("40P01"), race.sqlStates().toString());
    assertEquals(1, race.businessAttempts());
    assertEquals(1, race.ownerAttempts());
  }

  private Outcome outcome(Supplier<Row> action) {
    try {
      return new Outcome(action.get(), null);
    } catch (Throwable failure) {
      return new Outcome(null, failure);
    }
  }

  private void assertIndependentWorkers() {
    assertNotEquals(0, probe.businessPid.get());
    assertNotEquals(0, probe.ownerPid.get());
    assertNotEquals(
        probe.businessPid.get(),
        probe.ownerPid.get(),
        "concurrent workers must use independent PostgreSQL connections");
  }

  private void awaitBlockedBy(int waiterPid, int blockerPid) {
    long deadline = System.nanoTime() + OBSERVATION_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (isBlockedBy(waiterPid, blockerPid)) return;
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
    fail(
        "backend "
            + waiterPid
            + " was not observed waiting for backend "
            + blockerPid
            + "; blockers="
            + blockers(waiterPid));
  }

  private boolean isBlockedBy(int waiterPid, int blockerPid) {
    return blockers(waiterPid).contains(blockerPid);
  }

  private List<Integer> blockers(int backendPid) {
    return jdbc.queryForList(
        "select blocker from unnest(pg_blocking_pids(?)) blocker", Integer.class, backendPid);
  }

  private void assertOnlyBlockedBy(List<Integer> actual, int blockerPid) {
    assertEquals(Set.of(blockerPid), new LinkedHashSet<>(actual), actual.toString());
  }

  private void assertNoBlockers(int backendPid) {
    assertTrue(blockers(backendPid).isEmpty(), "unexpected blockers for backend " + backendPid);
  }

  private Map<Integer, CleanupObservation> observeCleanup(Collection<Integer> backendPids) {
    Map<Integer, CleanupObservation> cleanup = new LinkedHashMap<>();
    backendPids.forEach(pid -> cleanup.put(pid, assertTransactionClean(pid)));
    return cleanup;
  }

  private CleanupObservation assertTransactionClean(int backendPid) {
    assertNoBlockers(backendPid);
    List<BackendState> states =
        jdbc.query(
            "select state,xact_start,wait_event_type,wait_event,pid=pg_backend_pid() observer"
                + " from pg_stat_activity where pid=?",
            (rs, row) ->
                new BackendState(
                    rs.getString("state"),
                    rs.getObject("xact_start"),
                    rs.getString("wait_event_type"),
                    rs.getString("wait_event"),
                    rs.getBoolean("observer")),
            backendPid);
    if (states.isEmpty()) return CleanupObservation.NO_LONGER_OBSERVABLE;
    BackendState state = states.getFirst();
    if (state.observer()) return CleanupObservation.REUSED_BY_OBSERVER;
    assertEquals("idle", state.state(), state.description(backendPid));
    assertNull(state.xactStart(), state.description(backendPid));
    return CleanupObservation.IDLE_WITHOUT_TRANSACTION;
  }

  private void printEvidence(Race race) {
    System.out.printf(
        Locale.ROOT,
        "A3-E-%s-%s database=campus_buddy_mvp2_test businessPid=%d erasePid=%d"
            + " businessBlockedBy=%s eraseBlockedBy=%s sqlStates=%s"
            + " businessAttempts=%d eraseAttempts=%d owner=DELETED activitiesScrubbed=3"
            + " nonOwnerNotifications=1 nonOwnerDeliveryTasks=1 cleanup=%s%n",
        race.mode(),
        race.businessRole(),
        race.businessPid(),
        race.ownerPid(),
        race.businessBlockers(),
        race.ownerBlockers(),
        race.sqlStates(),
        race.businessAttempts(),
        race.ownerAttempts(),
        race.cleanup());
  }

  private void await(CountDownLatch latch, String description) {
    try {
      assertTrue(latch.await(FUTURE_TIMEOUT_SECONDS, SECONDS), "timed out waiting for " + description);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for " + description, error);
    }
  }

  private void cancelIfRunning(Future<?> future) {
    if (future != null && !future.isDone()) future.cancel(true);
  }

  private void closePool(ExecutorService pool) {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(FUTURE_TIMEOUT_SECONDS, SECONDS)) {
        pool.shutdownNow();
        assertTrue(
            pool.awaitTermination(FUTURE_TIMEOUT_SECONDS, SECONDS),
            "A3-E concurrency test worker pool did not terminate");
      }
    } catch (InterruptedException error) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while closing A3-E worker pool", error);
    }
  }

  private static Set<String> sqlStates(Throwable failure) {
    Set<String> states = new LinkedHashSet<>();
    Set<Throwable> seenCauses = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = failure; cause != null && seenCauses.add(cause); cause = cause.getCause()) {
      if (!(cause instanceof SQLException sql)) continue;
      Set<SQLException> seenSqlExceptions = Collections.newSetFromMap(new IdentityHashMap<>());
      for (SQLException current = sql;
          current != null && seenSqlExceptions.add(current);
          current = current.getNextException()) {
        if (current.getSQLState() != null) states.add(current.getSQLState());
      }
    }
    return states;
  }

  private enum Mode {
    ERASE_FIRST,
    BUSINESS_FIRST
  }

  private enum Role {
    APPLY,
    EXIT,
    WITHDRAW,
    OWNER_ERASE;

    boolean business() {
      return this != OWNER_ERASE;
    }
  }

  private record Scenario(
      UUID target,
      List<UUID> ownerActivities,
      Map<UUID, String> activityStatuses,
      Row application,
      UUID idempotencyKey,
      SideEffects sideEffects) {}

  private record Outcome(Row value, Throwable failure) {
    boolean succeeded() {
      return failure == null && value != null;
    }

    boolean hasSqlState(String state) {
      return failure != null && sqlStates(failure).contains(state);
    }

    String description() {
      return succeeded()
          ? "committed"
          : failure == null
              ? "empty"
              : failure.getClass().getName() + " sqlStates=" + sqlStates(failure);
    }
  }

  private record Race(
      Mode mode,
      Role businessRole,
      Outcome business,
      Outcome owner,
      int businessPid,
      int ownerPid,
      List<Integer> businessBlockers,
      List<Integer> ownerBlockers,
      Set<String> sqlStates,
      int businessAttempts,
      int ownerAttempts,
      Map<Integer, CleanupObservation> cleanup) {}

  private record SideEffects(
      long notifications,
      long deliveryTasks,
      long readyDeliveryTasks,
      long idempotencyRecords) {}

  private record BackendState(
      String state, Object xactStart, String waitEventType, String waitEvent, boolean observer) {
    String description(int backendPid) {
      return "backend="
          + backendPid
          + " state="
          + state
          + " xactStart="
          + xactStart
          + " waitEventType="
          + waitEventType
          + " waitEvent="
          + waitEvent;
    }
  }

  private enum CleanupObservation {
    IDLE_WITHOUT_TRANSACTION,
    REUSED_BY_OBSERVER,
    NO_LONGER_OBSERVABLE
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class HookConfiguration {
    @Bean
    TeamNotificationEraseProbe teamNotificationEraseProbe(JdbcTemplate jdbc) {
      return new TeamNotificationEraseProbe(jdbc);
    }
  }

  @Aspect
  @Order(20)
  static class TeamNotificationEraseProbe {
    private final JdbcTemplate jdbc;
    private final ThreadLocal<Role> role = new ThreadLocal<>();
    private final Map<Role, AtomicInteger> attempts = new EnumMap<>(Role.class);
    private final AtomicBoolean enabled = new AtomicBoolean();
    final AtomicInteger businessPid = new AtomicInteger();
    final AtomicInteger ownerPid = new AtomicInteger();
    final AtomicInteger deadlockEvents = new AtomicInteger();
    final Queue<Integer> businessPids = new ConcurrentLinkedQueue<>();
    final Queue<Integer> ownerPids = new ConcurrentLinkedQueue<>();
    final Set<String> observedSqlStates = new ConcurrentSkipListSet<>();
    volatile Mode mode = Mode.ERASE_FIRST;
    volatile Role businessRole = Role.APPLY;
    volatile CountDownLatch ownerUserUpdateRequested = new CountDownLatch(1);
    volatile CountDownLatch ownerUserLocked = new CountDownLatch(1);
    volatile CountDownLatch ownerActivitiesLocked = new CountDownLatch(1);
    volatile CountDownLatch allowOwnerCompletion = new CountDownLatch(1);
    volatile CountDownLatch businessReferenceRequested = new CountDownLatch(1);
    volatile CountDownLatch businessUsersLocked = new CountDownLatch(1);
    volatile CountDownLatch businessActivityLocked = new CountDownLatch(1);
    volatile CountDownLatch allowBusinessCompletion = new CountDownLatch(1);

    TeamNotificationEraseProbe(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
      for (Role value : Role.values()) attempts.put(value, new AtomicInteger());
    }

    void prepare(Mode nextMode, Role nextBusinessRole) {
      releaseAll();
      attempts.values().forEach(value -> value.set(0));
      businessPid.set(0);
      ownerPid.set(0);
      deadlockEvents.set(0);
      businessPids.clear();
      ownerPids.clear();
      observedSqlStates.clear();
      mode = nextMode;
      businessRole = nextBusinessRole;
      ownerUserUpdateRequested = new CountDownLatch(1);
      ownerUserLocked = new CountDownLatch(1);
      ownerActivitiesLocked = new CountDownLatch(1);
      allowOwnerCompletion = new CountDownLatch(1);
      businessReferenceRequested = new CountDownLatch(1);
      businessUsersLocked = new CountDownLatch(1);
      businessActivityLocked = new CountDownLatch(1);
      allowBusinessCompletion = new CountDownLatch(1);
      enabled.set(true);
    }

    void disable() {
      enabled.set(false);
      role.remove();
    }

    void releaseAll() {
      ownerUserUpdateRequested.countDown();
      ownerUserLocked.countDown();
      ownerActivitiesLocked.countDown();
      allowOwnerCompletion.countDown();
      businessReferenceRequested.countDown();
      businessUsersLocked.countDown();
      businessActivityLocked.countDown();
      allowBusinessCompletion.countDown();
    }

    Row call(Role current, Supplier<Row> action) {
      if (role.get() != null) throw new IllegalStateException("A3-E lock role already assigned");
      if (TransactionSynchronizationManager.isActualTransactionActive())
        throw new IllegalStateException("A3-E worker must enter through a production transaction proxy");
      role.set(current);
      try {
        return action.get();
      } finally {
        role.remove();
      }
    }

    int attempts(Role current) {
      return attempts.get(current).get();
    }

    Set<Integer> allPids() {
      Set<Integer> pids = new LinkedHashSet<>(businessPids);
      pids.addAll(ownerPids);
      return pids;
    }

    @Around(
        "execution(* edu.campusbuddy.team.application.TeamService.apply(..))"
            + " || execution(* edu.campusbuddy.team.application.TeamService.exit(..))"
            + " || execution(* edu.campusbuddy.team.application.TeamService.resolve(..))"
            + " || execution(* edu.campusbuddy.identity.application.IdentityService.erase(..))")
    Object observeAttempt(ProceedingJoinPoint point) throws Throwable {
      Role current = role.get();
      if (!enabled.get() || current == null) return point.proceed();
      attempts.get(current).incrementAndGet();
      try {
        return point.proceed();
      } catch (Throwable failure) {
        Set<String> states = sqlStates(failure);
        observedSqlStates.addAll(states);
        if (states.contains("40P01")) deadlockEvents.incrementAndGet();
        throw failure;
      }
    }

    @Around("execution(* edu.campusbuddy.identity.infrastructure.IdentityRepository.lock(..))")
    Object coordinateOwnerUserLock(ProceedingJoinPoint point) throws Throwable {
      Role current = role.get();
      if (!enabled.get() || current != Role.OWNER_ERASE) return point.proceed();
      configureTimeouts();
      int pid = backendPid();
      ownerPid.compareAndSet(0, pid);
      ownerPids.add(pid);
      ownerUserUpdateRequested.countDown();
      Object result = point.proceed();
      ownerUserLocked.countDown();
      return result;
    }

    @Around(
        "execution(*"
            + " edu.campusbuddy.identity.application.IdentityService.lockUsersAndReferences(..))")
    Object observeBusinessMixedUserLocks(ProceedingJoinPoint point) throws Throwable {
      Role current = role.get();
      if (!enabled.get() || current == null || !current.business()) return point.proceed();
      configureTimeouts();
      int pid = backendPid();
      businessPid.compareAndSet(0, pid);
      businessPids.add(pid);
      businessReferenceRequested.countDown();
      Object result = point.proceed();
      businessUsersLocked.countDown();
      return result;
    }

    @Around(
        "execution(* edu.campusbuddy.activity.application.ActivityService.lock(..))"
            + " || execution(* edu.campusbuddy.activity.application.ActivityService.lockOpen(..))")
    Object holdBusinessAfterActivityLock(ProceedingJoinPoint point) throws Throwable {
      Object result = point.proceed();
      Role current = role.get();
      if (!enabled.get() || current != businessRole) return result;
      businessActivityLocked.countDown();
      if (mode == Mode.BUSINESS_FIRST)
        await(allowBusinessCompletion, "allow business transaction to complete");
      return result;
    }

    @Around("execution(* edu.campusbuddy.activity.application.ActivityService.scrubOwner(..))")
    Object holdOwnerAfterAllActivityLocks(ProceedingJoinPoint point) throws Throwable {
      Object result = point.proceed();
      Role current = role.get();
      if (!enabled.get() || current != Role.OWNER_ERASE) return result;
      ownerActivitiesLocked.countDown();
      if (mode == Mode.ERASE_FIRST)
        await(allowOwnerCompletion, "allow owner erase transaction to complete");
      return result;
    }

    private void configureTimeouts() {
      jdbc.execute("set local lock_timeout = '8s'");
      jdbc.execute("set local statement_timeout = '10s'");
    }

    private int backendPid() {
      return Objects.requireNonNull(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
    }

    private void await(CountDownLatch latch, String description) {
      try {
        if (!latch.await(FUTURE_TIMEOUT_SECONDS, SECONDS))
          throw new AssertionError("timed out waiting to " + description);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting to " + description, error);
      }
    }
  }
}
