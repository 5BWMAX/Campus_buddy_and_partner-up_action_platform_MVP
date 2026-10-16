package edu.campusbuddy;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.Row;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Import(TransactionRetryDeadlockTest.HookConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class TransactionRetryDeadlockTest extends PgIntegrationSupport {
  private static final int FUTURE_TIMEOUT_SECONDS = 20;
  private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(4);

  @Autowired DeadlockProbe probe;

  @Test
  void orderedReferenceLockAvoidsDeadlockAndCommitsEachBusinessEffectOnce() throws Exception {
    String database = jdbc.queryForObject("select current_database()", String.class);
    assertEquals("campus_buddy_mvp2_test", database);

    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    UUID message =
        chat.send(
                ALICE,
                group,
                Row.of("clientMessageId", UUID.randomUUID(), "body", "retry deadlock target"))
            .id("id");
    long expectedVersion = part(activity, BOB).num("version");
    long reportsBefore = sql.count("select count(*) from report");
    long noticesBefore =
        sql.count(
            "select count(*) from notification where recipient_id=? and type='REPORT_SUBMITTED'",
            BOB);
    long tasksBefore =
        sql.count(
            "select count(*) from delivery_task d join notification n on n.event_id=d.event_id"
                + " and n.recipient_id=d.recipient_id where n.recipient_id=? and"
                + " n.type='REPORT_SUBMITTED'",
            BOB);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Row> confirmFuture = null, reportFuture = null;
    probe.prepare();
    try {
      confirmFuture =
          pool.submit(
              () ->
                  probe.call(
                      Role.CONFIRM,
                      () -> {
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                        return team.confirm(
                            BOB,
                            activity,
                            Row.of("status", "CONFIRMED", "expectedVersion", expectedVersion));
                      }));
      await(probe.userLocked, "confirmation transaction to lock BOB's user row");

      reportFuture =
          pool.submit(
              () ->
                  probe.call(
                      Role.REPORT,
                      () -> {
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                        return governance.report(
                            BOB,
                            Row.of(
                                "targetType",
                                "MESSAGE",
                                "targetId",
                                message,
                                "reason",
                                "transaction retry deadlock verification"));
                      }));
      await(probe.reportReferenceRequested, "report transaction to request BOB's reference lock");
      assertNotEquals(
          probe.confirmPid.get(),
          probe.reportPid.get(),
          "first attempts must use independent PostgreSQL connections");

      awaitBlockedBy(probe.reportPid.get(), probe.confirmPid.get());
      boolean referenceWaitObserved = true;
      probe.allowConfirm.countDown();
      await(probe.confirmActivityLocked, "confirmation transaction to acquire the activity lock");
      assertTrue(isBlockedBy(probe.reportPid.get(), probe.confirmPid.get()));
      assertNoBlockers(probe.confirmPid.get());
      boolean oneWayWaitObserved = true;
      probe.allowConfirmCompletion.countDown();

      Row confirmation = confirmFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      Row report = reportFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);

      assertTrue(referenceWaitObserved);
      assertTrue(oneWayWaitObserved);
      assertEquals("SUBMITTED", report.text("status"));
      assertEquals("CONFIRMED", confirmation.text("confirmation"));
      assertEquals("ACTIVE", confirmation.text("status"));
      assertEquals(0, probe.deadlockEvents.get(), probe.description());
      assertTrue(probe.deadlockSqlStates.isEmpty(), probe.description());
      assertNull(probe.deadlockVictim.get(), probe.description());
      assertEquals(1, probe.reportAttempts.get(), probe.description());
      assertEquals(1, probe.confirmAttempts.get(), probe.description());
      assertEquals(0, probe.attemptEntriesWithTransaction.get());
      assertEquals(0, probe.deadlocksCaughtInsideTransaction.get());
      assertEquals(probe.reportAttempts.get(), probe.reportAttemptPids.size());
      assertEquals(probe.confirmAttempts.get(), probe.confirmAttemptPids.size());

      UUID reportId = report.id("reportId");
      UUID reportEvent =
          UUID.nameUUIDFromBytes((reportId + ":submitted").getBytes(StandardCharsets.UTF_8));
      Row storedReport = sql.must("select * from report where id=?", reportId);
      assertEquals(BOB, storedReport.id("reporterId"));
      assertEquals("MESSAGE", storedReport.text("targetType"));
      assertEquals(message, storedReport.id("targetId"));
      assertEquals("SUBMITTED", storedReport.text("status"));
      assertEquals(reportsBefore + 1, sql.count("select count(*) from report"));
      assertEquals(
          noticesBefore + 1,
          sql.count(
              "select count(*) from notification where recipient_id=? and"
                  + " type='REPORT_SUBMITTED'",
              BOB));
      assertEquals(
          1,
          sql.count(
              "select count(*) from notification where event_id=? and recipient_id=? and"
                  + " type='REPORT_SUBMITTED'",
              reportEvent,
              BOB));
      assertEquals(
          tasksBefore + 1,
          sql.count(
              "select count(*) from delivery_task d join notification n on"
                  + " n.event_id=d.event_id and n.recipient_id=d.recipient_id where"
                  + " n.recipient_id=? and n.type='REPORT_SUBMITTED'",
              BOB));
      Row task =
          sql.must(
              "select * from delivery_task where event_id=? and recipient_id=?",
              reportEvent,
              BOB);
      assertEquals("READY", task.text("status"));
      assertEquals(0, task.num("attempts"));
      assertEquals(
          0,
          sql.count(
              "select count(*) from delivery_attempt where task_id=?", task.id("id")));
      Row storedParticipation = part(activity, BOB);
      assertEquals("ACTIVE", storedParticipation.text("status"));
      assertEquals("CONFIRMED", storedParticipation.text("confirmation"));
      assertEquals(expectedVersion + 1, storedParticipation.num("version"));

      Map<Integer, CleanupObservation> cleanup = new LinkedHashMap<>();
      probe.allAttemptPids().forEach(pid -> cleanup.put(pid, assertTransactionClean(pid)));
      System.out.printf(
          Locale.ROOT,
          "A1-D2 database=%s reportPids=%s confirmPids=%s referenceWait=%s"
              + " confirmBlockers=none sqlState40P01=false reportAttempts=%d confirmAttempts=%d"
              + " finalReport=SUBMITTED finalConfirmation=CONFIRMED reports=1 notices=1"
              + " deliveryTasks=1 deliveryStatus=READY cleanup=%s%n",
          database,
          probe.reportAttemptPids,
          probe.confirmAttemptPids,
          referenceWaitObserved,
          probe.reportAttempts.get(),
          probe.confirmAttempts.get(),
          cleanup);
    } finally {
      probe.releaseAll();
      cancelIfRunning(reportFuture);
      cancelIfRunning(confirmFuture);
      try {
        closePool(pool);
      } finally {
        probe.disable();
      }
    }
  }

  private void awaitBlockedBy(int waiterPid, int blockerPid) {
    long deadline = System.nanoTime() + OBSERVATION_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (isBlockedBy(waiterPid, blockerPid)) return;
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
    fail("backend " + waiterPid + " was not observed waiting for backend " + blockerPid);
  }

  private boolean isBlockedBy(int waiterPid, int blockerPid) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select ? = any(pg_blocking_pids(?))", Boolean.class, blockerPid, waiterPid));
  }

  private void assertNoBlockers(int backendPid) {
    Integer blockers =
        jdbc.queryForObject("select cardinality(pg_blocking_pids(?))", Integer.class, backendPid);
    assertEquals(0, Objects.requireNonNull(blockers));
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

  private void await(CountDownLatch latch, String description) {
    try {
      assertTrue(latch.await(FUTURE_TIMEOUT_SECONDS, SECONDS), "timed out waiting to " + description);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting to " + description, error);
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
            "transaction retry deadlock worker pool did not terminate");
      }
    } catch (InterruptedException error) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while closing retry deadlock worker pool", error);
    }
  }

  enum Role {
    REPORT,
    CONFIRM
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
    DeadlockProbe deadlockProbe(JdbcTemplate jdbc) {
      return new DeadlockProbe(jdbc);
    }
  }

  @Aspect
  @Order(20)
  static class DeadlockProbe {
    private final JdbcTemplate jdbc;
    private final ThreadLocal<Role> role = new ThreadLocal<>();
    private final AtomicBoolean enabled = new AtomicBoolean();
    private final AtomicBoolean firstConfirmPaused = new AtomicBoolean();
    private final AtomicBoolean firstReportSignalled = new AtomicBoolean();
    final AtomicInteger reportAttempts = new AtomicInteger();
    final AtomicInteger confirmAttempts = new AtomicInteger();
    final AtomicInteger deadlockEvents = new AtomicInteger();
    final AtomicInteger attemptEntriesWithTransaction = new AtomicInteger();
    final AtomicInteger deadlocksCaughtInsideTransaction = new AtomicInteger();
    final AtomicInteger reportPid = new AtomicInteger();
    final AtomicInteger confirmPid = new AtomicInteger();
    final AtomicReference<Role> deadlockVictim = new AtomicReference<>();
    final Queue<Integer> reportAttemptPids = new ConcurrentLinkedQueue<>();
    final Queue<Integer> confirmAttemptPids = new ConcurrentLinkedQueue<>();
    final Set<String> deadlockSqlStates = new ConcurrentSkipListSet<>();
    volatile CountDownLatch userLocked = new CountDownLatch(1);
    volatile CountDownLatch allowConfirm = new CountDownLatch(1);
    volatile CountDownLatch reportReferenceRequested = new CountDownLatch(1);
    volatile CountDownLatch confirmActivityLocked = new CountDownLatch(1);
    volatile CountDownLatch allowConfirmCompletion = new CountDownLatch(1);

    DeadlockProbe(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    void prepare() {
      releaseAll();
      reportAttempts.set(0);
      confirmAttempts.set(0);
      deadlockEvents.set(0);
      attemptEntriesWithTransaction.set(0);
      deadlocksCaughtInsideTransaction.set(0);
      reportPid.set(0);
      confirmPid.set(0);
      deadlockVictim.set(null);
      reportAttemptPids.clear();
      confirmAttemptPids.clear();
      deadlockSqlStates.clear();
      firstConfirmPaused.set(false);
      firstReportSignalled.set(false);
      userLocked = new CountDownLatch(1);
      allowConfirm = new CountDownLatch(1);
      reportReferenceRequested = new CountDownLatch(1);
      confirmActivityLocked = new CountDownLatch(1);
      allowConfirmCompletion = new CountDownLatch(1);
      enabled.set(true);
    }

    void disable() {
      enabled.set(false);
      role.remove();
    }

    void releaseAll() {
      userLocked.countDown();
      allowConfirm.countDown();
      reportReferenceRequested.countDown();
      confirmActivityLocked.countDown();
      allowConfirmCompletion.countDown();
    }

    <T> T call(Role current, Supplier<T> action) {
      if (role.get() != null) throw new IllegalStateException("deadlock role already assigned");
      role.set(current);
      try {
        return action.get();
      } finally {
        role.remove();
      }
    }

    @Around("execution(* edu.campusbuddy.governance.application.GovernanceService.report(..))")
    Object reportAttempt(ProceedingJoinPoint point) throws Throwable {
      if (!enabled.get() || role.get() != Role.REPORT) return point.proceed();
      return attempt(point, Role.REPORT, reportAttempts);
    }

    @Around("execution(* edu.campusbuddy.team.application.TeamService.confirm(..))")
    Object confirmAttempt(ProceedingJoinPoint point) throws Throwable {
      if (!enabled.get() || role.get() != Role.CONFIRM) return point.proceed();
      return attempt(point, Role.CONFIRM, confirmAttempts);
    }

    private Object attempt(ProceedingJoinPoint point, Role current, AtomicInteger attempts)
        throws Throwable {
      attempts.incrementAndGet();
      if (TransactionSynchronizationManager.isActualTransactionActive())
        attemptEntriesWithTransaction.incrementAndGet();
      try {
        return point.proceed();
      } catch (Throwable failure) {
        Set<String> states = sqlStates(failure);
        if (states.contains("40P01")) {
          deadlockEvents.incrementAndGet();
          deadlockVictim.compareAndSet(null, current);
          deadlockSqlStates.add("40P01");
          if (TransactionSynchronizationManager.isActualTransactionActive())
            deadlocksCaughtInsideTransaction.incrementAndGet();
        }
        throw failure;
      }
    }

    @Around("execution(* edu.campusbuddy.identity.application.IdentityService.lockUsers(..))")
    Object holdFirstConfirmationAfterUserLock(ProceedingJoinPoint point) throws Throwable {
      Object result = point.proceed();
      if (!enabled.get() || role.get() != Role.CONFIRM) return result;
      configureTimeouts();
      int pid = backendPid();
      confirmAttemptPids.add(pid);
      if (confirmAttempts.get() == 1 && firstConfirmPaused.compareAndSet(false, true)) {
        confirmPid.set(pid);
        userLocked.countDown();
        await(allowConfirm, "allow the confirmation transaction to request the activity lock");
      }
      return result;
    }

    @Around(
        "execution(*"
            + " edu.campusbuddy.identity.application.IdentityService.lockUserReferences(..))")
    Object observeReportReferenceRequest(ProceedingJoinPoint point) throws Throwable {
      if (!enabled.get() || role.get() != Role.REPORT) return point.proceed();
      configureTimeouts();
      int pid = backendPid();
      reportAttemptPids.add(pid);
      if (reportAttempts.get() == 1 && firstReportSignalled.compareAndSet(false, true)) {
        reportPid.set(pid);
        reportReferenceRequested.countDown();
      }
      return point.proceed();
    }

    @Around("execution(* edu.campusbuddy.activity.application.ActivityService.lock(..))")
    Object holdFirstConfirmationAfterActivityLock(ProceedingJoinPoint point) throws Throwable {
      Object result = point.proceed();
      if (!enabled.get() || role.get() != Role.CONFIRM || confirmAttempts.get() != 1) return result;
      confirmActivityLocked.countDown();
      await(allowConfirmCompletion, "allow the confirmation transaction to complete");
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

    Set<Integer> allAttemptPids() {
      Set<Integer> pids = new LinkedHashSet<>(reportAttemptPids);
      pids.addAll(confirmAttemptPids);
      return pids;
    }

    String description() {
      return "reportAttempts="
          + reportAttempts
          + ", confirmAttempts="
          + confirmAttempts
          + ", deadlockVictim="
          + deadlockVictim
          + ", sqlStates="
          + deadlockSqlStates;
    }

    private static Set<String> sqlStates(Throwable failure) {
      Set<String> states = new LinkedHashSet<>();
      Set<Throwable> seenCauses = Collections.newSetFromMap(new IdentityHashMap<>());
      for (Throwable cause = failure;
          cause != null && seenCauses.add(cause);
          cause = cause.getCause()) {
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
  }
}
