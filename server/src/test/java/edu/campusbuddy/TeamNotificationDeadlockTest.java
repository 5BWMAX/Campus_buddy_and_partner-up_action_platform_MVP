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

@Import(TeamNotificationDeadlockTest.HookConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class TeamNotificationDeadlockTest extends PgIntegrationSupport {
  private static final int FUTURE_TIMEOUT_SECONDS = 20;
  private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(4);

  @Autowired TeamNotificationLockProbe probe;

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
  void applyWaitsForOwnerConfirmationBeforeRequestingTheActivityLock() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID idempotencyKey = UUID.randomUUID();
    long ownerVersion = part(activity, ALICE).num("version");
    SideEffects before = sideEffects();

    OwnerFirstRace race =
        runOwnerFirst(
            Role.APPLY,
            () ->
                team.apply(
                    BOB,
                    activity,
                    Row.of("note", "A3-N owner-first apply"),
                    idempotencyKey),
            () -> confirmOwner(activity, ownerVersion));

    assertCommitted(race.business(), race.owner());
    assertEquals("PENDING", race.business().value().text("status"));
    assertEquals("PENDING", application(activity).text("status"));
    assertEquals("CONFIRMED", part(activity, ALICE).text("confirmation"));
    assertExpectedSideEffects(before);
    assertIdempotencyRecord(BOB, "apply:" + activity, idempotencyKey);
    printOwnerFirstEvidence("A3-N-post-owner-first-apply", race, "application=PENDING");
  }

  @Test
  void exitWaitsForOwnerConfirmationBeforeRequestingTheActivityLock() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, activity, apply(BOB, activity));
    UUID idempotencyKey = UUID.randomUUID();
    long ownerVersion = part(activity, ALICE).num("version");
    SideEffects before = sideEffects();

    OwnerFirstRace race =
        runOwnerFirst(
            Role.EXIT,
            () ->
                team.exit(
                    BOB,
                    activity,
                    Row.of("reason", "A3-N owner-first exit"),
                    idempotencyKey),
            () -> confirmOwner(activity, ownerVersion));

    assertCommitted(race.business(), race.owner());
    assertEquals("LEFT", race.business().value().text("status"));
    assertEquals("LEFT", part(activity, BOB).text("status"));
    assertEquals("CONFIRMED", part(activity, ALICE).text("confirmation"));
    assertExpectedSideEffects(before);
    assertIdempotencyRecord(BOB, "exit:" + activity, idempotencyKey);
    printOwnerFirstEvidence("A3-N-post-owner-first-exit", race, "participation=LEFT");
  }

  @Test
  void withdrawalWaitsForOwnerConfirmationBeforeRequestingTheActivityLock() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    Row application = apply(BOB, activity);
    long ownerVersion = part(activity, ALICE).num("version");
    SideEffects before = sideEffects();

    OwnerFirstRace race =
        runOwnerFirst(
            Role.WITHDRAW,
            () -> withdraw(BOB, activity, application),
            () -> confirmOwner(activity, ownerVersion));

    assertCommitted(race.business(), race.owner());
    assertEquals("WITHDRAWN", race.business().value().text("status"));
    assertEquals("WITHDRAWN", application(activity).text("status"));
    assertEquals("CONFIRMED", part(activity, ALICE).text("confirmation"));
    assertExpectedSideEffects(before);
    printOwnerFirstEvidence("A3-N-post-owner-first-withdraw", race, "application=WITHDRAWN");
  }

  @Test
  void ownerConfirmationWaitsWhileApplyHoldsTheOrderedLocks() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID idempotencyKey = UUID.randomUUID();
    long ownerVersion = part(activity, ALICE).num("version");
    SideEffects before = sideEffects();

    BusinessFirstRace race =
        runBusinessFirst(
            Role.APPLY,
            () ->
                team.apply(
                    BOB,
                    activity,
                    Row.of("note", "A3-N business-first apply"),
                    idempotencyKey),
            () -> confirmOwner(activity, ownerVersion));

    assertCommitted(race.business(), race.owner());
    assertEquals("PENDING", application(activity).text("status"));
    assertEquals("CONFIRMED", part(activity, ALICE).text("confirmation"));
    assertExpectedSideEffects(before);
    assertIdempotencyRecord(BOB, "apply:" + activity, idempotencyKey);
    printBusinessFirstEvidence("A3-N-post-business-first-apply", race, "application=PENDING");
  }

  @Test
  void ownerConfirmationWaitsWhileExitHoldsTheOrderedLocks() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, activity, apply(BOB, activity));
    UUID idempotencyKey = UUID.randomUUID();
    long ownerVersion = part(activity, ALICE).num("version");
    SideEffects before = sideEffects();

    BusinessFirstRace race =
        runBusinessFirst(
            Role.EXIT,
            () ->
                team.exit(
                    BOB,
                    activity,
                    Row.of("reason", "A3-N business-first exit"),
                    idempotencyKey),
            () -> confirmOwner(activity, ownerVersion));

    assertCommitted(race.business(), race.owner());
    assertEquals("LEFT", part(activity, BOB).text("status"));
    assertEquals("CONFIRMED", part(activity, ALICE).text("confirmation"));
    assertExpectedSideEffects(before);
    assertIdempotencyRecord(BOB, "exit:" + activity, idempotencyKey);
    printBusinessFirstEvidence("A3-N-post-business-first-exit", race, "participation=LEFT");
  }

  @Test
  void ownerConfirmationWaitsWhileWithdrawalHoldsTheOrderedLocks() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    Row application = apply(BOB, activity);
    long ownerVersion = part(activity, ALICE).num("version");
    SideEffects before = sideEffects();

    BusinessFirstRace race =
        runBusinessFirst(
            Role.WITHDRAW,
            () -> withdraw(BOB, activity, application),
            () -> confirmOwner(activity, ownerVersion));

    assertCommitted(race.business(), race.owner());
    assertEquals("WITHDRAWN", application(activity).text("status"));
    assertEquals("CONFIRMED", part(activity, ALICE).text("confirmation"));
    assertExpectedSideEffects(before);
    printBusinessFirstEvidence(
        "A3-N-post-business-first-withdraw", race, "application=WITHDRAWN");
  }

  private OwnerFirstRace runOwnerFirst(
      Role businessRole, Supplier<Row> businessAction, Supplier<Row> ownerAction) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Outcome> ownerFuture = null, businessFuture = null;
    probe.prepare(Mode.OWNER_FIRST, businessRole);
    try {
      ownerFuture =
          pool.submit(() -> outcome(() -> probe.call(Role.OWNER_CONFIRM, ownerAction)));
      await(probe.ownerUserLocked, "owner confirmation to acquire user FOR UPDATE");

      businessFuture = pool.submit(() -> outcome(() -> probe.call(businessRole, businessAction)));
      await(
          probe.businessReferenceRequested,
          businessRole + " transaction to request the owner KEY SHARE lock");
      assertIndependentWorkers();

      awaitBlockedBy(probe.businessPid.get(), probe.ownerPid.get());
      List<Integer> beforeActivity = blockers(probe.businessPid.get());
      assertOnlyBlockedBy(beforeActivity, probe.ownerPid.get());
      assertEquals(
          1,
          probe.businessUsersLocked.getCount(),
          "business transaction must still be inside the mixed user lock call");
      assertEquals(
          1,
          probe.businessActivityLocked.getCount(),
          "business transaction must not request the activity lock while waiting for owner");

      probe.allowOwnerActivityRequest.countDown();
      await(
          probe.ownerActivityLocked,
          "owner confirmation to acquire activity FOR UPDATE while business waits for owner");
      List<Integer> afterOwnerActivity = blockers(probe.businessPid.get());
      assertOnlyBlockedBy(afterOwnerActivity, probe.ownerPid.get());
      assertNoBlockers(probe.ownerPid.get());
      assertFalse(isBlockedBy(probe.ownerPid.get(), probe.businessPid.get()));
      probe.allowOwnerCompletion.countDown();

      Outcome owner = ownerFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      Outcome business = businessFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertEquals(
          0,
          probe.businessActivityLocked.getCount(),
          "business transaction must acquire the activity lock after owner commit");
      assertNoDeadlockOrRetry(businessRole, business, Role.OWNER_CONFIRM, owner);
      Map<Integer, CleanupObservation> cleanup = observeCleanup(probe.allPids());
      return new OwnerFirstRace(
          business,
          owner,
          probe.businessPid.get(),
          probe.ownerPid.get(),
          beforeActivity,
          afterOwnerActivity,
          Set.copyOf(probe.observedSqlStates),
          probe.attempts(businessRole),
          probe.attempts(Role.OWNER_CONFIRM),
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

  private BusinessFirstRace runBusinessFirst(
      Role businessRole, Supplier<Row> businessAction, Supplier<Row> ownerAction) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Outcome> businessFuture = null, ownerFuture = null;
    probe.prepare(Mode.BUSINESS_FIRST, businessRole);
    try {
      businessFuture = pool.submit(() -> outcome(() -> probe.call(businessRole, businessAction)));
      await(
          probe.businessActivityLocked,
          businessRole + " transaction to acquire ordered user locks and activity FOR UPDATE");
      assertEquals(0, probe.businessUsersLocked.getCount());

      ownerFuture =
          pool.submit(() -> outcome(() -> probe.call(Role.OWNER_CONFIRM, ownerAction)));
      await(
          probe.ownerUserUpdateRequested,
          "owner confirmation to request owner user FOR UPDATE");
      assertIndependentWorkers();

      awaitBlockedBy(probe.ownerPid.get(), probe.businessPid.get());
      List<Integer> ownerBlockers = blockers(probe.ownerPid.get());
      assertOnlyBlockedBy(ownerBlockers, probe.businessPid.get());
      assertEquals(
          1,
          probe.ownerUserLocked.getCount(),
          "owner transaction must still be inside the user FOR UPDATE call");
      assertEquals(
          1,
          probe.ownerActivityLocked.getCount(),
          "owner transaction must not request the activity lock while waiting for the user");
      assertNoBlockers(probe.businessPid.get());
      assertFalse(isBlockedBy(probe.businessPid.get(), probe.ownerPid.get()));
      probe.allowBusinessCompletion.countDown();

      Outcome business = businessFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      Outcome owner = ownerFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertEquals(
          0,
          probe.ownerActivityLocked.getCount(),
          "owner transaction must acquire the activity lock after business commit");
      assertNoDeadlockOrRetry(businessRole, business, Role.OWNER_CONFIRM, owner);
      Map<Integer, CleanupObservation> cleanup = observeCleanup(probe.allPids());
      return new BusinessFirstRace(
          business,
          owner,
          probe.businessPid.get(),
          probe.ownerPid.get(),
          ownerBlockers,
          Set.copyOf(probe.observedSqlStates),
          probe.attempts(businessRole),
          probe.attempts(Role.OWNER_CONFIRM),
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

  private Row confirmOwner(UUID activity, long expectedVersion) {
    return team.confirm(
        ALICE,
        activity,
        Row.of("status", "CONFIRMED", "expectedVersion", expectedVersion));
  }

  private Row withdraw(UUID actor, UUID activity, Row application) {
    return team.resolve(
        actor,
        activity,
        application.id("id"),
        Row.of(
            "round",
            application.num("round"),
            "expectedVersion",
            application.num("version")),
        true);
  }

  private Row application(UUID activity) {
    return sql.must("select * from application where activity_id=? and user_id=?", activity, BOB);
  }

  private SideEffects sideEffects() {
    return new SideEffects(
        count("notification"), count("delivery_task"), countReadyDeliveryTasks());
  }

  private void assertExpectedSideEffects(SideEffects before) {
    SideEffects after = sideEffects();
    assertEquals(2, after.notifications() - before.notifications());
    assertEquals(2, after.deliveryTasks() - before.deliveryTasks());
    assertEquals(2, after.readyDeliveryTasks() - before.readyDeliveryTasks());
  }

  private long countReadyDeliveryTasks() {
    return sql.count("select count(*) from delivery_task where status='READY'");
  }

  private long count(String table) {
    return sql.count("select count(*) from " + table);
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

  private void assertNoDeadlockOrRetry(
      Role firstRole, Outcome first, Role secondRole, Outcome second) {
    assertFalse(first.hasSqlState("40P01"), first.description());
    assertFalse(second.hasSqlState("40P01"), second.description());
    assertEquals(0, probe.deadlockEvents.get());
    assertFalse(probe.observedSqlStates.contains("40P01"), probe.observedSqlStates.toString());
    assertEquals(1, probe.attempts(firstRole));
    assertEquals(1, probe.attempts(secondRole));
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

  private void printOwnerFirstEvidence(
      String name, OwnerFirstRace race, String businessResult) {
    System.out.printf(
        Locale.ROOT,
        "%s database=campus_buddy_mvp2_test ownerPid=%d businessPid=%d"
            + " businessBlockedBeforeActivity=%s businessBlockedAfterOwnerActivity=%s"
            + " ownerBlockers=[] sqlStates=%s businessAttempts=%d ownerAttempts=%d"
            + " %s cleanup=%s%n",
        name,
        race.ownerPid(),
        race.businessPid(),
        race.beforeActivityBlockers(),
        race.afterOwnerActivityBlockers(),
        race.sqlStates(),
        race.businessAttempts(),
        race.ownerAttempts(),
        businessResult,
        race.cleanup());
  }

  private void printBusinessFirstEvidence(
      String name, BusinessFirstRace race, String businessResult) {
    System.out.printf(
        Locale.ROOT,
        "%s database=campus_buddy_mvp2_test businessPid=%d ownerPid=%d"
            + " ownerBlockedAtUser=%s businessBlockers=[] sqlStates=%s"
            + " businessAttempts=%d ownerAttempts=%d %s cleanup=%s%n",
        name,
        race.businessPid(),
        race.ownerPid(),
        race.ownerBlockers(),
        race.sqlStates(),
        race.businessAttempts(),
        race.ownerAttempts(),
        businessResult,
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
            "A3-N concurrency test worker pool did not terminate");
      }
    } catch (InterruptedException error) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while closing A3-N worker pool", error);
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
    OWNER_FIRST,
    BUSINESS_FIRST
  }

  private enum Role {
    APPLY,
    EXIT,
    WITHDRAW,
    OWNER_CONFIRM;

    boolean business() {
      return this != OWNER_CONFIRM;
    }
  }

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

  private record OwnerFirstRace(
      Outcome business,
      Outcome owner,
      int businessPid,
      int ownerPid,
      List<Integer> beforeActivityBlockers,
      List<Integer> afterOwnerActivityBlockers,
      Set<String> sqlStates,
      int businessAttempts,
      int ownerAttempts,
      Map<Integer, CleanupObservation> cleanup) {}

  private record BusinessFirstRace(
      Outcome business,
      Outcome owner,
      int businessPid,
      int ownerPid,
      List<Integer> ownerBlockers,
      Set<String> sqlStates,
      int businessAttempts,
      int ownerAttempts,
      Map<Integer, CleanupObservation> cleanup) {}

  private record SideEffects(long notifications, long deliveryTasks, long readyDeliveryTasks) {}

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
    TeamNotificationLockProbe teamNotificationLockProbe(JdbcTemplate jdbc) {
      return new TeamNotificationLockProbe(jdbc);
    }
  }

  @Aspect
  @Order(20)
  static class TeamNotificationLockProbe {
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
    volatile Mode mode = Mode.OWNER_FIRST;
    volatile Role businessRole = Role.APPLY;
    volatile CountDownLatch ownerUserUpdateRequested = new CountDownLatch(1);
    volatile CountDownLatch ownerUserLocked = new CountDownLatch(1);
    volatile CountDownLatch allowOwnerActivityRequest = new CountDownLatch(1);
    volatile CountDownLatch ownerActivityLocked = new CountDownLatch(1);
    volatile CountDownLatch allowOwnerCompletion = new CountDownLatch(1);
    volatile CountDownLatch businessReferenceRequested = new CountDownLatch(1);
    volatile CountDownLatch businessUsersLocked = new CountDownLatch(1);
    volatile CountDownLatch businessActivityLocked = new CountDownLatch(1);
    volatile CountDownLatch allowBusinessCompletion = new CountDownLatch(1);

    TeamNotificationLockProbe(JdbcTemplate jdbc) {
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
      allowOwnerActivityRequest = new CountDownLatch(1);
      ownerActivityLocked = new CountDownLatch(1);
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
      allowOwnerActivityRequest.countDown();
      ownerActivityLocked.countDown();
      allowOwnerCompletion.countDown();
      businessReferenceRequested.countDown();
      businessUsersLocked.countDown();
      businessActivityLocked.countDown();
      allowBusinessCompletion.countDown();
    }

    Row call(Role current, Supplier<Row> action) {
      if (role.get() != null) throw new IllegalStateException("A3-N lock role already assigned");
      if (TransactionSynchronizationManager.isActualTransactionActive())
        throw new IllegalStateException("A3-N worker must enter through a production transaction proxy");
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
            + " || execution(* edu.campusbuddy.team.application.TeamService.confirm(..))")
    Object observeBusinessAttempt(ProceedingJoinPoint point) throws Throwable {
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

    @Around("execution(* edu.campusbuddy.identity.application.IdentityService.lockUsers(..))")
    Object coordinateOwnerUserLock(ProceedingJoinPoint point) throws Throwable {
      Role current = role.get();
      if (!enabled.get() || current != Role.OWNER_CONFIRM) return point.proceed();
      configureTimeouts();
      int pid = backendPid();
      ownerPid.compareAndSet(0, pid);
      ownerPids.add(pid);
      ownerUserUpdateRequested.countDown();
      Object result = point.proceed();
      ownerUserLocked.countDown();
      if (mode == Mode.OWNER_FIRST)
        await(allowOwnerActivityRequest, "allow owner confirmation to request activity lock");
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
    Object holdAfterActivityLock(ProceedingJoinPoint point) throws Throwable {
      Object result = point.proceed();
      Role current = role.get();
      if (!enabled.get() || current == null) return result;
      if (current == Role.OWNER_CONFIRM) {
        ownerActivityLocked.countDown();
        if (mode == Mode.OWNER_FIRST)
          await(allowOwnerCompletion, "allow owner confirmation to complete");
      } else if (current == businessRole) {
        businessActivityLocked.countDown();
        if (mode == Mode.BUSINESS_FIRST)
          await(allowBusinessCompletion, "allow business transaction to complete");
      }
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
