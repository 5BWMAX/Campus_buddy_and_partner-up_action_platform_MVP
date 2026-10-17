package edu.campusbuddy;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(GovernanceExitDeadlockTest.HookConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class GovernanceExitDeadlockTest extends PgIntegrationSupport {
  private static final int FUTURE_TIMEOUT_SECONDS = 15;
  private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(4);

  @Autowired PlatformTransactionManager transactions;
  @Autowired ExitLockProbe probe;

  @Test
  void messageReportWaitsForMemberExitWithoutADeadlockCycle() throws Exception {
    String database = jdbc.queryForObject("select current_database()", String.class);
    assertEquals("campus_buddy_mvp2_test", database);

    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    UUID message =
        chat.send(
                ALICE,
                group,
                Row.of("clientMessageId", UUID.randomUUID(), "body", "deadlock target"))
            .id("id");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch userLocked = new CountDownLatch(1),
        allowExit = new CountDownLatch(1),
        reportStarted = new CountDownLatch(1);
    AtomicInteger exitPid = new AtomicInteger(), reportPid = new AtomicInteger();
    Future<Outcome> exitFuture = null, reportFuture = null;
    probe.prepare();
    try {
      exitFuture =
          pool.submit(
              () ->
                  probe.exit(
                      () ->
                          outcome(
                              () ->
                                  transaction(
                                      () -> {
                                        configureTimeouts();
                                        exitPid.set(backendPid());
                                        identity.lockUsers(List.of(BOB));
                                        userLocked.countDown();
                                        await(
                                            allowExit,
                                            "let the exit transaction request the activity lock");
                                        return team.exit(
                                            BOB,
                                            activity,
                                            Row.of("reason", "deadlock verification"),
                                            UUID.randomUUID());
                                      }))));
      await(userLocked, "exit transaction to lock BOB's user row");

      reportFuture =
          pool.submit(
              () ->
                  outcome(
                      () ->
                          transaction(
                              () -> {
                                configureTimeouts();
                                reportPid.set(backendPid());
                                reportStarted.countDown();
                                return governance.report(
                                    BOB,
                                    Row.of(
                                        "targetType",
                                        "MESSAGE",
                                        "targetId",
                                        message,
                                        "reason",
                                        "deadlock verification"));
                              })));
      await(reportStarted, "message report transaction to start");
      assertNotEquals(exitPid.get(), reportPid.get(), "workers must use independent connections");

      awaitBlockedBy(reportPid.get(), exitPid.get());
      allowExit.countDown();
      await(probe.activityLocked, "exit transaction to acquire the activity lock");
      assertTrue(isBlockedBy(reportPid.get(), exitPid.get()));
      assertNoBlockers(exitPid.get());
      assertFalse(isBlockedBy(exitPid.get(), reportPid.get()));
      boolean oneWayWaitObserved = true;
      probe.allowExitCompletion.countDown();

      Outcome exit = exitFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      Outcome report = reportFuture.get(FUTURE_TIMEOUT_SECONDS, SECONDS);

      assertTrue(oneWayWaitObserved, "report must wait for the exit transaction's user lock");
      assertTrue(exit.succeeded(), describe(report, exit));
      assertFalse(exit.hasDeadlock(), describe(report, exit));
      assertFalse(report.hasDeadlock(), describe(report, exit));
      Problem denied = assertInstanceOf(Problem.class, report.failure(), describe(report, exit));
      assertEquals(404, denied.status);
      assertEquals("RESOURCE_NOT_FOUND", denied.code);

      int reportCount =
          Objects.requireNonNull(
              jdbc.queryForObject(
                  "select count(*) from report where reporter_id=? and target_type='MESSAGE' and"
                      + " target_id=?",
                  Integer.class,
                   BOB,
                   message));
      String participationStatus = part(activity, BOB).text("status");
      assertEquals("LEFT", exit.value().text("status"));
      assertEquals(0, reportCount);
      assertEquals("LEFT", participationStatus);

      CleanupObservation exitCleanup = assertTransactionClean(exitPid.get());
      CleanupObservation reportCleanup = assertTransactionClean(reportPid.get());
      System.out.printf(
          Locale.ROOT,
          "A1-D1 database=%s exitPid=%d reportPid=%d reportBlockedByExit=%s"
              + " exitBlockedByReport=false sqlState40P01=false exit=LEFT"
              + " report=RESOURCE_NOT_FOUND exitCleanup=%s reportCleanup=%s%n",
          database,
          exitPid.get(),
          reportPid.get(),
          oneWayWaitObserved,
          exitCleanup,
          reportCleanup);
    } finally {
      probe.releaseAll();
      userLocked.countDown();
      reportStarted.countDown();
      allowExit.countDown();
      cancelIfRunning(reportFuture);
      cancelIfRunning(exitFuture);
      closePool(pool);
    }
  }

  private <T> T transaction(Supplier<T> action) {
    return new TransactionTemplate(transactions).execute(status -> action.get());
  }

  private Outcome outcome(Supplier<Row> action) {
    try {
      return new Outcome(action.get(), null);
    } catch (Throwable failure) {
      return new Outcome(null, failure);
    }
  }

  private void configureTimeouts() {
    jdbc.execute("set local lock_timeout = '8s'");
    jdbc.execute("set local statement_timeout = '10s'");
  }

  private int backendPid() {
    return Objects.requireNonNull(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
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

  private String describe(Outcome report, Outcome exit) {
    return "report=" + report.description() + ", exit=" + exit.description();
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
            "deadlock test worker pool did not terminate");
      }
    } catch (InterruptedException error) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while closing deadlock test worker pool", error);
    }
  }

  private record Outcome(Row value, Throwable failure) {
    boolean succeeded() {
      return value != null && failure == null;
    }

    boolean hasDeadlock() {
      return failure != null && sqlStates(failure).contains("40P01");
    }

    String description() {
      if (succeeded()) return "committed";
      return failure == null
          ? "empty"
          : failure.getClass().getName() + " sqlStates=" + sqlStates(failure);
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
    ExitLockProbe exitLockProbe() {
      return new ExitLockProbe();
    }
  }

  @Aspect
  @Order(20)
  static class ExitLockProbe {
    private final ThreadLocal<Boolean> exitThread = new ThreadLocal<>();
    volatile CountDownLatch activityLocked = new CountDownLatch(1);
    volatile CountDownLatch allowExitCompletion = new CountDownLatch(1);

    void prepare() {
      releaseAll();
      activityLocked = new CountDownLatch(1);
      allowExitCompletion = new CountDownLatch(1);
    }

    <T> T exit(Supplier<T> action) {
      if (exitThread.get() != null) throw new IllegalStateException("exit role already assigned");
      exitThread.set(true);
      try {
        return action.get();
      } finally {
        exitThread.remove();
      }
    }

    void releaseAll() {
      activityLocked.countDown();
      allowExitCompletion.countDown();
    }

    @Around("execution(* edu.campusbuddy.activity.application.ActivityService.lock(..))")
    Object holdExitAfterActivityLock(ProceedingJoinPoint point) throws Throwable {
      Object result = point.proceed();
      if (!Boolean.TRUE.equals(exitThread.get())) return result;
      activityLocked.countDown();
      awaitLatch(allowExitCompletion, "allow the exit transaction to complete");
      return result;
    }

    private void awaitLatch(CountDownLatch latch, String description) {
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
