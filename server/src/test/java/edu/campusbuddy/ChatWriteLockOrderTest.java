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

@Import(ChatWriteLockOrderTest.HookConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class ChatWriteLockOrderTest extends PgIntegrationSupport {
  private static final int FUTURE_TIMEOUT_SECONDS = 20;
  private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(4);

  @Autowired ChatLockProbe probe;

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
  void a2T1SendWaitsForExitUserLockBeforeRequestingTheActivityLock() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    UUID clientMessageId = UUID.randomUUID();

    UserFirstRace race =
        runUserFirst(
            Role.EXIT,
            () ->
                team.exit(
                    BOB,
                    activity,
                    Row.of("reason", "A2 send versus exit"),
                    UUID.randomUUID()),
            Role.SEND,
            () ->
                chat.send(
                    BOB,
                    group,
                    Row.of(
                        "clientMessageId", clientMessageId, "body", "must be denied after exit")));

    assertTrue(race.member().succeeded(), race.description());
    assertEquals("LEFT", race.member().value().text("status"));
    Problem denied = assertInstanceOf(Problem.class, race.chat().failure(), race.description());
    assertEquals(404, denied.status);
    assertEquals("RESOURCE_NOT_FOUND", denied.code);
    assertEquals("LEFT", part(activity, BOB).text("status"));
    assertEquals(
        0,
        sql.count(
            "select count(*) from message where group_id=? and sender_id=? and client_message_id=?",
            group,
            BOB,
            clientMessageId));
    printUserFirstEvidence("A2-T1", race, "exit=LEFT send=RESOURCE_NOT_FOUND messages=0");
  }

  @Test
  void a2T2SendWaitsForConfirmationThenUsesTheCommittedMembership() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    long expectedVersion = part(activity, BOB).num("version");
    UUID clientMessageId = UUID.randomUUID();

    UserFirstRace race =
        runUserFirst(
            Role.CONFIRM,
            () ->
                team.confirm(
                    BOB,
                    activity,
                    Row.of("status", "CONFIRMED", "expectedVersion", expectedVersion)),
            Role.SEND,
            () ->
                chat.send(
                    BOB,
                    group,
                    Row.of("clientMessageId", clientMessageId, "body", "sent after confirmation")));

    assertTrue(race.member().succeeded(), race.description());
    assertTrue(race.chat().succeeded(), race.description());
    assertEquals("CONFIRMED", race.member().value().text("confirmation"));
    Row storedParticipation = part(activity, BOB);
    assertEquals("ACTIVE", storedParticipation.text("status"));
    assertEquals("CONFIRMED", storedParticipation.text("confirmation"));
    assertEquals(expectedVersion + 1, storedParticipation.num("version"));
    assertEquals(
        1,
        sql.count(
            "select count(*) from message where group_id=? and sender_id=? and client_message_id=?",
            group,
            BOB,
            clientMessageId));
    printUserFirstEvidence("A2-T2", race, "confirm=CONFIRMED send=COMMITTED messages=1");
  }

  @Test
  void a2T3PinWaitsForOwnerConfirmationBeforeRequestingTheActivityLock() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    long expectedVersion = part(activity, ALICE).num("version");

    UserFirstRace race =
        runUserFirst(
            Role.CONFIRM,
            () ->
                team.confirm(
                    ALICE,
                    activity,
                    Row.of("status", "CONFIRMED", "expectedVersion", expectedVersion)),
            Role.PIN,
            () ->
                chat.pin(
                    ALICE,
                    group,
                    Row.of("body", "owner pin after confirmation", "expectedVersion", 0)));

    assertTrue(race.member().succeeded(), race.description());
    assertTrue(race.chat().succeeded(), race.description());
    assertEquals("CONFIRMED", race.member().value().text("confirmation"));
    assertEquals(1, race.chat().value().num("version"));
    Row pin = sql.must("select * from group_pin where group_id=?", group);
    assertEquals(ALICE, pin.id("editorId"));
    assertEquals("owner pin after confirmation", pin.text("body"));
    assertEquals(1, pin.num("version"));
    assertEquals(1, sql.count("select count(*) from group_pin where group_id=?", group));
    printUserFirstEvidence("A2-T3", race, "confirm=CONFIRMED pinVersion=1 rows=1");
  }

  @Test
  void a2T4ExitWaitsAtTheUserLockWhileSendHoldsTheOrderedLocks() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    UUID clientMessageId = UUID.randomUUID();

    ReverseRace race =
        runChatFirst(
            Role.SEND,
            () ->
                chat.send(
                    BOB,
                    group,
                    Row.of("clientMessageId", clientMessageId, "body", "committed before exit")),
            Role.EXIT,
            () ->
                team.exit(
                    BOB,
                    activity,
                    Row.of("reason", "A2 reverse start"),
                    UUID.randomUUID()));

    assertTrue(race.chat().succeeded(), race.description());
    assertTrue(race.member().succeeded(), race.description());
    assertEquals("LEFT", race.member().value().text("status"));
    assertEquals("LEFT", part(activity, BOB).text("status"));
    assertEquals(
        1,
        sql.count(
            "select count(*) from message where group_id=? and sender_id=? and client_message_id=?",
            group,
            BOB,
            clientMessageId));
    System.out.printf(
        Locale.ROOT,
        "A2-T4 chatPid=%d exitPid=%d exitBlockedByChat=%s chatBlockers=[]"
            + " sqlStates=%s send=COMMITTED exit=LEFT cleanup=%s%n",
        race.chatPid(),
        race.memberPid(),
        race.memberBlockers(),
        race.sqlStates(),
        race.cleanup());
  }

  @Test
  void a2T5MessageIdempotencyAndPinVersionSemanticsRemainUnchanged() {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    UUID clientMessageId = UUID.randomUUID();
    long notificationsBefore = sql.count("select count(*) from notification");
    long tasksBefore = sql.count("select count(*) from delivery_task");

    Row first =
        chat.send(
            BOB,
            group,
            Row.of("clientMessageId", clientMessageId, "body", "idempotent message"));
    Row replay =
        chat.send(
            BOB,
            group,
            Row.of("clientMessageId", clientMessageId, "body", "idempotent message"));
    assertEquals(first.id("id"), replay.id("id"));
    assertEquals(
        1,
        sql.count(
            "select count(*) from message where group_id=? and sender_id=? and client_message_id=?",
            group,
            BOB,
            clientMessageId));
    Problem reused =
        assertThrows(
            Problem.class,
            () ->
                chat.send(
                    BOB,
                    group,
                    Row.of("clientMessageId", clientMessageId, "body", "different body")));
    assertEquals(409, reused.status);
    assertEquals("KEY_REUSED", reused.code);

    Row firstPin =
        chat.pin(ALICE, group, Row.of("body", "first pin", "expectedVersion", 0));
    Row secondPin =
        chat.pin(ALICE, group, Row.of("body", "second pin", "expectedVersion", 1));
    assertEquals(1, firstPin.num("version"));
    assertEquals(2, secondPin.num("version"));
    Problem stale =
        assertThrows(
            Problem.class,
            () -> chat.pin(ALICE, group, Row.of("body", "stale pin", "expectedVersion", 1)));
    assertEquals(409, stale.status);
    assertEquals("VERSION_CONFLICT", stale.code);
    Row storedPin = sql.must("select * from group_pin where group_id=?", group);
    assertEquals("second pin", storedPin.text("body"));
    assertEquals(2, storedPin.num("version"));
    assertEquals(1, sql.count("select count(*) from group_pin where group_id=?", group));
    // V3 adds one group-message notification; replay and pin changes must not duplicate it.
    assertEquals(notificationsBefore + 1, sql.count("select count(*) from notification"));
    assertEquals(tasksBefore + 1, sql.count("select count(*) from delivery_task"));
    assertEquals(1, sql.count("select count(*) from notification where type='GROUP_MESSAGE' and recipient_id=?", ALICE));
  }

  private UserFirstRace runUserFirst(
      Role memberRole,
      Supplier<Row> memberAction,
      Role chatRole,
      Supplier<Row> chatAction)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Outcome> memberFuture = null, chatFuture = null;
    probe.prepare(Mode.USER_FIRST);
    try {
      memberFuture = pool.submit(() -> outcome(() -> probe.call(memberRole, memberAction)));
      await(probe.memberUserLocked, memberRole + " transaction to acquire the user FOR UPDATE");

      chatFuture = pool.submit(() -> outcome(() -> probe.call(chatRole, chatAction)));
      await(probe.chatReferenceRequested, chatRole + " transaction to request user KEY SHARE");
      assertIndependentWorkers();

      awaitBlockedBy(probe.chatPid.get(), probe.memberPid.get());
      List<Integer> beforeActivity = blockers(probe.chatPid.get());
      assertOnlyBlockedBy(beforeActivity, probe.memberPid.get());
      assertEquals(
          1,
          probe.chatActivityLocked.getCount(),
          "chat must still be inside the user reference lock call");

      probe.allowMemberActivityRequest.countDown();
      await(
          probe.memberActivityLocked,
          memberRole + " transaction to acquire the activity FOR UPDATE");
      List<Integer> afterActivity = blockers(probe.chatPid.get());
      assertOnlyBlockedBy(afterActivity, probe.memberPid.get());
      assertNoBlockers(probe.memberPid.get());
      assertFalse(isBlockedBy(probe.memberPid.get(), probe.chatPid.get()));
      probe.allowMemberCompletion.countDown();

      Outcome member = memberFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      Outcome chat = chatFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertNoDeadlockOrRetry(memberRole, member, chatRole, chat);
      Map<Integer, CleanupObservation> cleanup = observeCleanup(probe.allPids());
      return new UserFirstRace(
          member,
          chat,
          probe.memberPid.get(),
          probe.chatPid.get(),
          beforeActivity,
          afterActivity,
          Set.copyOf(probe.observedSqlStates),
          cleanup);
    } finally {
      probe.releaseAll();
      cancelIfRunning(chatFuture);
      cancelIfRunning(memberFuture);
      try {
        closePool(pool);
      } finally {
        probe.disable();
      }
    }
  }

  private ReverseRace runChatFirst(
      Role chatRole, Supplier<Row> chatAction, Role memberRole, Supplier<Row> memberAction)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Outcome> chatFuture = null, memberFuture = null;
    probe.prepare(Mode.CHAT_FIRST);
    try {
      chatFuture = pool.submit(() -> outcome(() -> probe.call(chatRole, chatAction)));
      await(
          probe.chatActivityLocked,
          chatRole + " transaction to acquire user KEY SHARE and activity FOR UPDATE");

      memberFuture = pool.submit(() -> outcome(() -> probe.call(memberRole, memberAction)));
      await(
          probe.memberUserUpdateRequested,
          memberRole + " transaction to request the user FOR UPDATE");
      assertIndependentWorkers();

      awaitBlockedBy(probe.memberPid.get(), probe.chatPid.get());
      List<Integer> memberBlockers = blockers(probe.memberPid.get());
      assertOnlyBlockedBy(memberBlockers, probe.chatPid.get());
      assertEquals(
          1,
          probe.memberUserLocked.getCount(),
          "member transaction must still be inside the user FOR UPDATE call");
      assertNoBlockers(probe.chatPid.get());
      assertFalse(isBlockedBy(probe.chatPid.get(), probe.memberPid.get()));
      probe.allowChatCompletion.countDown();

      Outcome chat = chatFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      Outcome member = memberFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertEquals(
          0,
          probe.memberActivityLocked.getCount(),
          "member transaction must reach the activity lock after the chat commit");
      assertNoDeadlockOrRetry(chatRole, chat, memberRole, member);
      Map<Integer, CleanupObservation> cleanup = observeCleanup(probe.allPids());
      return new ReverseRace(
          chat,
          member,
          probe.chatPid.get(),
          probe.memberPid.get(),
          memberBlockers,
          Set.copyOf(probe.observedSqlStates),
          cleanup);
    } finally {
      probe.releaseAll();
      cancelIfRunning(memberFuture);
      cancelIfRunning(chatFuture);
      try {
        closePool(pool);
      } finally {
        probe.disable();
      }
    }
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

  private void assertIndependentWorkers() {
    assertNotEquals(0, probe.memberPid.get());
    assertNotEquals(0, probe.chatPid.get());
    assertNotEquals(
        probe.memberPid.get(),
        probe.chatPid.get(),
        "concurrent workers must use independent PostgreSQL connections");
  }

  private Outcome outcome(Supplier<Row> action) {
    try {
      return new Outcome(action.get(), null);
    } catch (Throwable failure) {
      return new Outcome(null, failure);
    }
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

  private void printUserFirstEvidence(String name, UserFirstRace race, String businessResult) {
    System.out.printf(
        Locale.ROOT,
        "%s memberPid=%d chatPid=%d chatBlockedBeforeActivity=%s"
            + " chatBlockedAfterMemberActivity=%s memberBlockers=[] sqlStates=%s %s cleanup=%s%n",
        name,
        race.memberPid(),
        race.chatPid(),
        race.beforeActivityBlockers(),
        race.afterActivityBlockers(),
        race.sqlStates(),
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
            "A2 concurrency test worker pool did not terminate");
      }
    } catch (InterruptedException error) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while closing A2 concurrency test worker pool", error);
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
    USER_FIRST,
    CHAT_FIRST
  }

  private enum Role {
    SEND,
    PIN,
    EXIT,
    CONFIRM;

    boolean chat() {
      return this == SEND || this == PIN;
    }

    boolean member() {
      return this == EXIT || this == CONFIRM;
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

  private record UserFirstRace(
      Outcome member,
      Outcome chat,
      int memberPid,
      int chatPid,
      List<Integer> beforeActivityBlockers,
      List<Integer> afterActivityBlockers,
      Set<String> sqlStates,
      Map<Integer, CleanupObservation> cleanup) {
    String description() {
      return "member=" + member.description() + ", chat=" + chat.description();
    }
  }

  private record ReverseRace(
      Outcome chat,
      Outcome member,
      int chatPid,
      int memberPid,
      List<Integer> memberBlockers,
      Set<String> sqlStates,
      Map<Integer, CleanupObservation> cleanup) {
    String description() {
      return "chat=" + chat.description() + ", member=" + member.description();
    }
  }

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
    ChatLockProbe chatLockProbe(JdbcTemplate jdbc) {
      return new ChatLockProbe(jdbc);
    }
  }

  @Aspect
  @Order(20)
  static class ChatLockProbe {
    private final JdbcTemplate jdbc;
    private final ThreadLocal<Role> role = new ThreadLocal<>();
    private final Map<Role, AtomicInteger> attempts = new EnumMap<>(Role.class);
    private final AtomicBoolean enabled = new AtomicBoolean();
    final AtomicInteger memberPid = new AtomicInteger();
    final AtomicInteger chatPid = new AtomicInteger();
    final AtomicInteger deadlockEvents = new AtomicInteger();
    final Queue<Integer> memberPids = new ConcurrentLinkedQueue<>();
    final Queue<Integer> chatPids = new ConcurrentLinkedQueue<>();
    final Set<String> observedSqlStates = new ConcurrentSkipListSet<>();
    volatile Mode mode = Mode.USER_FIRST;
    volatile CountDownLatch memberUserUpdateRequested = new CountDownLatch(1);
    volatile CountDownLatch memberUserLocked = new CountDownLatch(1);
    volatile CountDownLatch allowMemberActivityRequest = new CountDownLatch(1);
    volatile CountDownLatch memberActivityLocked = new CountDownLatch(1);
    volatile CountDownLatch allowMemberCompletion = new CountDownLatch(1);
    volatile CountDownLatch chatReferenceRequested = new CountDownLatch(1);
    volatile CountDownLatch chatActivityLocked = new CountDownLatch(1);
    volatile CountDownLatch allowChatCompletion = new CountDownLatch(1);

    ChatLockProbe(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
      for (Role value : Role.values()) attempts.put(value, new AtomicInteger());
    }

    void prepare(Mode nextMode) {
      releaseAll();
      attempts.values().forEach(value -> value.set(0));
      memberPid.set(0);
      chatPid.set(0);
      deadlockEvents.set(0);
      memberPids.clear();
      chatPids.clear();
      observedSqlStates.clear();
      mode = nextMode;
      memberUserUpdateRequested = new CountDownLatch(1);
      memberUserLocked = new CountDownLatch(1);
      allowMemberActivityRequest = new CountDownLatch(1);
      memberActivityLocked = new CountDownLatch(1);
      allowMemberCompletion = new CountDownLatch(1);
      chatReferenceRequested = new CountDownLatch(1);
      chatActivityLocked = new CountDownLatch(1);
      allowChatCompletion = new CountDownLatch(1);
      enabled.set(true);
    }

    void disable() {
      enabled.set(false);
      role.remove();
    }

    void releaseAll() {
      memberUserUpdateRequested.countDown();
      memberUserLocked.countDown();
      allowMemberActivityRequest.countDown();
      memberActivityLocked.countDown();
      allowMemberCompletion.countDown();
      chatReferenceRequested.countDown();
      chatActivityLocked.countDown();
      allowChatCompletion.countDown();
    }

    Row call(Role current, Supplier<Row> action) {
      if (role.get() != null) throw new IllegalStateException("A2 lock role already assigned");
      if (TransactionSynchronizationManager.isActualTransactionActive())
        throw new IllegalStateException("A2 worker must enter through a production transaction proxy");
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
      Set<Integer> pids = new LinkedHashSet<>(memberPids);
      pids.addAll(chatPids);
      return pids;
    }

    @Around(
        "execution(* edu.campusbuddy.chat.application.ChatService.send(..))"
            + " || execution(* edu.campusbuddy.chat.application.ChatService.pin(..))"
            + " || execution(* edu.campusbuddy.team.application.TeamService.exit(..))"
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

    @Around(
        "execution(* edu.campusbuddy.identity.application.IdentityService.lockUsers(..))"
            + " || execution(*"
            + " edu.campusbuddy.identity.application.IdentityService.lockUsersAndReferences(..))")
    Object holdMemberAfterUserUpdateLock(ProceedingJoinPoint point) throws Throwable {
      Role current = role.get();
      if (!enabled.get() || current == null || !current.member()) return point.proceed();
      configureTimeouts();
      int pid = backendPid();
      memberPid.compareAndSet(0, pid);
      memberPids.add(pid);
      memberUserUpdateRequested.countDown();
      Object result = point.proceed();
      memberUserLocked.countDown();
      if (mode == Mode.USER_FIRST)
        await(allowMemberActivityRequest, "allow member transaction to request activity lock");
      return result;
    }

    @Around(
        "execution(*"
            + " edu.campusbuddy.identity.application.IdentityService.lockUserReferences(..))")
    Object observeChatReferenceLock(ProceedingJoinPoint point) throws Throwable {
      Role current = role.get();
      if (!enabled.get() || current == null || !current.chat()) return point.proceed();
      configureTimeouts();
      int pid = backendPid();
      chatPid.compareAndSet(0, pid);
      chatPids.add(pid);
      chatReferenceRequested.countDown();
      return point.proceed();
    }

    @Around("execution(* edu.campusbuddy.activity.application.ActivityService.lock(..))")
    Object holdAfterActivityLock(ProceedingJoinPoint point) throws Throwable {
      Object result = point.proceed();
      Role current = role.get();
      if (!enabled.get() || current == null) return result;
      if (current.member()) {
        memberActivityLocked.countDown();
        if (mode == Mode.USER_FIRST)
          await(allowMemberCompletion, "allow member transaction to complete");
      } else if (current.chat()) {
        chatActivityLocked.countDown();
        if (mode == Mode.CHAT_FIRST) await(allowChatCompletion, "allow chat transaction to complete");
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
