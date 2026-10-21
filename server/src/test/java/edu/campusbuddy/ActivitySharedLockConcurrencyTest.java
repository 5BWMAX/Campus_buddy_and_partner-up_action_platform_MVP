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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class ActivitySharedLockConcurrencyTest extends PgIntegrationSupport {
  private static final int FUTURE_TIMEOUT_SECONDS = 12;
  private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(4);

  @Autowired PlatformTransactionManager transactions;

  @Test
  @Order(1)
  void t1SameActivityReadsAcquireCompatibleShareLocks() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, activity, apply(BOB, activity));
    identity.update(BOB, Row.of("contact", "shared-lock-private-contact"));
    identity.privacy(BOB, Row.of("contactVisibility", "GROUP", "locationVisibility", "HIDDEN"));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch firstHolding = new CountDownLatch(1),
        secondHolding = new CountDownLatch(1),
        release = new CountDownLatch(1);
    AtomicInteger firstPid = new AtomicInteger(), secondPid = new AtomicInteger();
    try {
      Future<List<Row>> first =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        firstPid.set(backendPid());
                        List<Row> rows = team.members(ALICE, activity);
                        firstHolding.countDown();
                        await(release, "release first same-activity reader");
                        return rows;
                      }));
      await(firstHolding, "first same-activity reader to hold the activity lock");
      Future<List<Row>> second =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        secondPid.set(backendPid());
                        List<Row> rows = team.members(BOB, activity);
                        secondHolding.countDown();
                        await(release, "release second same-activity reader");
                        return rows;
                      }));
      await(secondHolding, "second same-activity reader to acquire its shared lock");
      assertFalse(isBlockedBy(secondPid.get(), firstPid.get()));
      assertFalse(isBlockedBy(firstPid.get(), secondPid.get()));

      release.countDown();
      List<Row> firstRows = first.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      List<Row> secondRows = second.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertEquals(2, firstRows.size());
      assertEquals(2, secondRows.size());
      assertEquals("shared-lock-private-contact", member(firstRows, BOB).text("contact"));
      assertEquals("shared-lock-private-contact", member(secondRows, BOB).text("contact"));
      System.out.printf(
          Locale.ROOT,
          "P2-1 T1 compatible same-activity readers firstPid=%d secondPid=%d%n",
          firstPid.get(),
          secondPid.get());
    } finally {
      release.countDown();
      closePool(pool);
    }
  }

  @Test
  @Order(2)
  void t2ReadFirstMakesExitWaitAndExitRevokesLaterReads() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    chat.send(BOB, group, Row.of("clientMessageId", UUID.randomUUID(), "body", "baseline"));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch readerHolding = new CountDownLatch(1),
        writerStarted = new CountDownLatch(1),
        releaseReader = new CountDownLatch(1);
    AtomicInteger readerPid = new AtomicInteger(), writerPid = new AtomicInteger();
    try {
      Future<Row> reader =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        readerPid.set(backendPid());
                        Row page = chat.messages(BOB, group, null, 20);
                        readerHolding.countDown();
                        await(releaseReader, "release message reader");
                        return page;
                      }));
      await(readerHolding, "message reader to hold the activity lock");
      UUID key = UUID.randomUUID();
      Future<Row> writer =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        writerPid.set(backendPid());
                        writerStarted.countDown();
                        return team.exit(BOB, activity, Row.of("reason", "concurrency test"), key);
                      }));
      await(writerStarted, "exit writer to start");
      awaitBlockedBy(writerPid.get(), readerPid.get());

      releaseReader.countDown();
      assertEquals("ACTIVE", reader.get(FUTURE_TIMEOUT_SECONDS, SECONDS).text("accessMode"));
      assertEquals("LEFT", writer.get(FUTURE_TIMEOUT_SECONDS, SECONDS).text("status"));
      assertEquals(
          "RESOURCE_NOT_FOUND",
          assertThrows(Problem.class, () -> chat.messages(BOB, group, null, 20)).code);
      System.out.printf(
          Locale.ROOT,
          "P2-1 T2 read-before-exit readerPid=%d blockedWriterPid=%d%n",
          readerPid.get(),
          writerPid.get());
    } finally {
      releaseReader.countDown();
      closePool(pool);
    }
  }

  @Test
  @Order(3)
  void t3CancelFirstMakesReadWaitAndReadRechecksReadOnlyState() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch writerHolding = new CountDownLatch(1),
        readerStarted = new CountDownLatch(1),
        releaseWriter = new CountDownLatch(1);
    AtomicInteger writerPid = new AtomicInteger(), readerPid = new AtomicInteger();
    try {
      Future<Row> writer =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        writerPid.set(backendPid());
                        Row cancelled =
                            activities.cancel(
                                ALICE,
                                activity,
                                Row.of("expectedVersion", 0, "reason", "concurrency test"));
                        writerHolding.countDown();
                        await(releaseWriter, "commit cancellation");
                        return cancelled;
                      }));
      await(writerHolding, "cancellation writer to hold its uncommitted activity lock");
      Future<Row> reader =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        readerPid.set(backendPid());
                        readerStarted.countDown();
                        return chat.messages(BOB, group, null, 20);
                      }));
      await(readerStarted, "message reader behind cancellation to start");
      awaitBlockedBy(readerPid.get(), writerPid.get());

      releaseWriter.countDown();
      assertEquals("CANCELLED", writer.get(FUTURE_TIMEOUT_SECONDS, SECONDS).text("status"));
      assertEquals("READ_ONLY", reader.get(FUTURE_TIMEOUT_SECONDS, SECONDS).text("accessMode"));
      assertEquals(
          "GROUP_READ_ONLY",
          assertThrows(
                  Problem.class,
                  () ->
                      chat.send(
                          BOB,
                          group,
                          Row.of("clientMessageId", UUID.randomUUID(), "body", "too late")))
              .code);
      System.out.printf(
          Locale.ROOT,
          "P2-1 T3 cancel-before-read writerPid=%d blockedReaderPid=%d%n",
          writerPid.get(),
          readerPid.get());
    } finally {
      releaseWriter.countDown();
      closePool(pool);
    }
  }

  @Test
  @Order(4)
  void t4DifferentActivityReadsDoNotBlockEachOther() throws Exception {
    UUID firstActivity = activity(ALICE, 2, 7200, 10800);
    UUID secondActivity = activity(CARA, 2, 14400, 18000);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch firstHolding = new CountDownLatch(1),
        secondHolding = new CountDownLatch(1),
        release = new CountDownLatch(1);
    AtomicInteger firstPid = new AtomicInteger(), secondPid = new AtomicInteger();
    try {
      Future<List<Row>> first =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        firstPid.set(backendPid());
                        List<Row> rows = team.members(ALICE, firstActivity);
                        firstHolding.countDown();
                        await(release, "release first different-activity reader");
                        return rows;
                      }));
      await(firstHolding, "first activity reader to hold its row lock");
      Future<List<Row>> second =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        secondPid.set(backendPid());
                        List<Row> rows = team.members(CARA, secondActivity);
                        secondHolding.countDown();
                        await(release, "release second different-activity reader");
                        return rows;
                      }));
      await(secondHolding, "second activity reader to acquire a different row lock");
      assertFalse(isBlockedBy(secondPid.get(), firstPid.get()));
      assertFalse(isBlockedBy(firstPid.get(), secondPid.get()));

      release.countDown();
      assertEquals(1, first.get(FUTURE_TIMEOUT_SECONDS, SECONDS).size());
      assertEquals(1, second.get(FUTURE_TIMEOUT_SECONDS, SECONDS).size());
      System.out.printf(
          Locale.ROOT,
          "P2-1 T4 different activities independent firstPid=%d secondPid=%d%n",
          firstPid.get(),
          secondPid.get());
    } finally {
      release.countDown();
      closePool(pool);
    }
  }

  @Test
  @Order(5)
  void t5AuthorizationFailureAndRollbackReleaseActivityLocks() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    UUID message =
        chat.send(
                BOB,
                group,
                Row.of("clientMessageId", UUID.randomUUID(), "body", "blocked group message"))
            .id("id");
    transaction(
        () -> {
          activities.lockParticipants(activity, List.of(ADMIN));
          team.block(activity, true);
          return null;
        });

    AtomicInteger deniedPid = new AtomicInteger(), rollbackPid = new AtomicInteger();
    Problem denied =
        assertThrows(
            Problem.class,
            () ->
                transaction(
                    () -> {
                      deniedPid.set(backendPid());
                      return team.members(BOB, activity);
                    }));
    assertEquals("RESOURCE_NOT_FOUND", denied.code);
    assertNoBlockers(deniedPid.get());
    assertEquals(
        "RESOURCE_NOT_FOUND",
        assertThrows(Problem.class, () -> chat.messages(BOB, group, null, 20)).code);
    assertEquals(
        "RESOURCE_NOT_FOUND",
        assertThrows(
                Problem.class,
                () ->
                    governance.report(
                        BOB,
                        Row.of(
                            "targetType",
                            "MESSAGE",
                            "targetId",
                            message,
                            "reason",
                            "blocked group report")))
            .code);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> unblock =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        activities.lockParticipants(activity, List.of(ADMIN));
                        team.block(activity, false);
                        return null;
                      }));
      unblock.get(FUTURE_TIMEOUT_SECONDS, SECONDS);

      assertThrows(
          ForcedRollback.class,
          () ->
              transaction(
                  () -> {
                    rollbackPid.set(backendPid());
                    team.members(BOB, activity);
                    throw new ForcedRollback();
                  }));
      assertNoBlockers(rollbackPid.get());

      Future<Row> writer =
          pool.submit(
              () ->
                  activities.cancel(
                      ALICE,
                      activity,
                      Row.of("expectedVersion", 0, "reason", "after rollback")));
      assertEquals("CANCELLED", writer.get(FUTURE_TIMEOUT_SECONDS, SECONDS).text("status"));
      System.out.printf(
          Locale.ROOT,
          "P2-1 T5 locks released deniedPid=%d rollbackPid=%d%n",
          deniedPid.get(),
          rollbackPid.get());
    } finally {
      closePool(pool);
    }
  }

  @Test
  @Order(6)
  void t6LocalLockTimeoutRollsBackAndReportsActualSqlState() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch readerHolding = new CountDownLatch(1),
        waiterStarted = new CountDownLatch(1),
        releaseReader = new CountDownLatch(1);
    AtomicInteger readerPid = new AtomicInteger(), waiterPid = new AtomicInteger();
    UUID key = UUID.randomUUID();
    try {
      Future<Row> reader =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        readerPid.set(backendPid());
                        Row page = chat.messages(BOB, group, null, 20);
                        readerHolding.countDown();
                        await(releaseReader, "release lock-timeout reader");
                        return page;
                      }));
      await(readerHolding, "reader to hold the activity lock for timeout test");
      Future<DataAccessException> waiter =
          pool.submit(
              () -> {
                try {
                  transaction(
                      () -> {
                        jdbc.execute("set local lock_timeout = '5s'");
                        waiterPid.set(backendPid());
                        waiterStarted.countDown();
                        return team.exit(
                            BOB, activity, Row.of("reason", "timeout baseline"), key);
                      });
                  return null;
                } catch (DataAccessException error) {
                  return error;
                }
              });
      await(waiterStarted, "lock-timeout writer to start");
      awaitBlockedBy(waiterPid.get(), readerPid.get());

      DataAccessException error = waiter.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertNotNull(error, "the local lock timeout must fail the waiting transaction");
      assertInstanceOf(UncategorizedSQLException.class, error);
      assertEquals("55P03", sqlState(error));
      assertNoBlockers(waiterPid.get());

      releaseReader.countDown();
      reader.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      Row retry = team.exit(BOB, activity, Row.of("reason", "retry after timeout"), key);
      assertEquals("LEFT", retry.text("status"));
      System.out.printf(
          Locale.ROOT,
          "P2-1 T6 timeout holderPid=%d waiterPid=%d exception=%s sqlState=%s%n",
          readerPid.get(),
          waiterPid.get(),
          error.getClass().getName(),
          sqlState(error));
    } finally {
      releaseReader.countDown();
      closePool(pool);
    }
  }

  @Test
  @Order(7)
  void t7DetailsWithoutGroupDoNotCreateOneAndApprovalWaits() throws Exception {
    UUID activity = activity(ALICE, 2, 7200, 10800);
    Row application = apply(BOB, activity);
    identity.privacy(ALICE, Row.of("contactVisibility", "HIDDEN", "locationVisibility", "GROUP"));
    Row before = activities.details(ALICE, activity);
    assertNull(team.group(activity), "a details read must not create a temporary group");
    assertFalse(before.containsKey("groupId"));
    assertEquals("三楼东侧", before.text("exactLocation"));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch readerHolding = new CountDownLatch(1),
        writerStarted = new CountDownLatch(1),
        releaseReader = new CountDownLatch(1);
    AtomicInteger readerPid = new AtomicInteger(), writerPid = new AtomicInteger();
    try {
      Future<UUID> reader =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        readerPid.set(backendPid());
                        UUID group = team.requireActivityDetailsAccess(ALICE, activity);
                        readerHolding.countDown();
                        await(releaseReader, "release details access reader without group");
                        return group;
                      }));
      await(readerHolding, "details access reader without group to hold the activity lock");
      Future<Row> writer =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        writerPid.set(backendPid());
                        writerStarted.countDown();
                        return approve(ALICE, activity, application);
                      }));
      await(writerStarted, "approval creating the group to start");
      awaitBlockedBy(writerPid.get(), readerPid.get());

      releaseReader.countDown();
      assertNull(reader.get(FUTURE_TIMEOUT_SECONDS, SECONDS));
      UUID group = writer.get(FUTURE_TIMEOUT_SECONDS, SECONDS).id("groupId");
      Row after = activities.details(BOB, activity);
      assertEquals(group, after.id("groupId"));
      assertEquals("三楼东侧", after.text("exactLocation"));
      System.out.printf(
          Locale.ROOT,
          "P2-1 T7 no-group readerPid=%d blockedApprovalPid=%d groupCreatedAfterCommit=true%n",
          readerPid.get(),
          writerPid.get());
    } finally {
      releaseReader.countDown();
      closePool(pool);
    }
  }

  @Test
  @Order(8)
  void t8SendKeepsStrongActivityLockAgainstSharedReader() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch writerHolding = new CountDownLatch(1),
        readerStarted = new CountDownLatch(1),
        releaseWriter = new CountDownLatch(1);
    AtomicInteger writerPid = new AtomicInteger(), readerPid = new AtomicInteger();
    try {
      Future<Row> writer =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        writerPid.set(backendPid());
                        Row sent =
                            chat.send(
                                BOB,
                                group,
                                Row.of(
                                    "clientMessageId",
                                    UUID.randomUUID(),
                                    "body",
                                    "strong-lock send"));
                        writerHolding.countDown();
                        await(releaseWriter, "commit message send");
                        return sent;
                      }));
      await(writerHolding, "message sender to hold the activity strong lock");
      Future<Row> reader =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        readerPid.set(backendPid());
                        readerStarted.countDown();
                        return chat.messages(ALICE, group, null, 20);
                      }));
      await(readerStarted, "shared reader behind message send to start");
      awaitBlockedBy(readerPid.get(), writerPid.get());

      releaseWriter.countDown();
      assertNotNull(writer.get(FUTURE_TIMEOUT_SECONDS, SECONDS).id("id"));
      assertEquals("ACTIVE", reader.get(FUTURE_TIMEOUT_SECONDS, SECONDS).text("accessMode"));
      System.out.printf(
          Locale.ROOT,
          "P2-1 send strong lock writerPid=%d blockedReaderPid=%d%n",
          writerPid.get(),
          readerPid.get());
    } finally {
      releaseWriter.countDown();
      closePool(pool);
    }
  }

  @Test
  @Order(9)
  void t9PinKeepsStrongActivityLockAgainstSharedReader() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch writerHolding = new CountDownLatch(1),
        readerStarted = new CountDownLatch(1),
        releaseWriter = new CountDownLatch(1);
    AtomicInteger writerPid = new AtomicInteger(), readerPid = new AtomicInteger();
    try {
      Future<Row> writer =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        writerPid.set(backendPid());
                        Row pin =
                            chat.pin(
                                ALICE,
                                group,
                                Row.of("body", "strong-lock pin", "expectedVersion", 0));
                        writerHolding.countDown();
                        await(releaseWriter, "commit group pin");
                        return pin;
                      }));
      await(writerHolding, "pin writer to hold the activity strong lock");
      Future<Row> reader =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        readerPid.set(backendPid());
                        readerStarted.countDown();
                        return chat.messages(BOB, group, null, 20);
                      }));
      await(readerStarted, "shared reader behind group pin to start");
      awaitBlockedBy(readerPid.get(), writerPid.get());

      releaseWriter.countDown();
      Row pin = writer.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertEquals("strong-lock pin", pin.text("body"));
      assertEquals(1, pin.num("version"));
      assertEquals("ACTIVE", reader.get(FUTURE_TIMEOUT_SECONDS, SECONDS).text("accessMode"));
      System.out.printf(
          Locale.ROOT,
          "P2-1 pin strong lock writerPid=%d blockedReaderPid=%d%n",
          writerPid.get(),
          readerPid.get());
    } finally {
      releaseWriter.countDown();
      closePool(pool);
    }
  }

  @Test
  @Order(10)
  void t10MessageReportKeepsSharedLockForOuterTransaction() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    UUID message =
        chat.send(
                ALICE,
                group,
                Row.of("clientMessageId", UUID.randomUUID(), "body", "reportable message"))
            .id("id");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch reportHolding = new CountDownLatch(1),
        writerStarted = new CountDownLatch(1),
        releaseReport = new CountDownLatch(1);
    AtomicInteger reportPid = new AtomicInteger(), writerPid = new AtomicInteger();
    try {
      Future<Row> report =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        reportPid.set(backendPid());
                        Row submitted =
                            governance.report(
                                BOB,
                                Row.of(
                                    "targetType",
                                    "MESSAGE",
                                    "targetId",
                                    message,
                                    "reason",
                                    "transaction lock regression"));
                        reportHolding.countDown();
                        await(releaseReport, "commit message report");
                        return submitted;
                      }));
      await(reportHolding, "message report to retain its shared activity lock");
      Future<?> writer =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        writerPid.set(backendPid());
                        writerStarted.countDown();
                        activities.lock(activity);
                        team.block(activity, true);
                        return null;
                      }));
      await(writerStarted, "activity writer behind message report to start");
      awaitBlockedBy(writerPid.get(), reportPid.get());

      releaseReport.countDown();
      assertEquals("SUBMITTED", report.get(FUTURE_TIMEOUT_SECONDS, SECONDS).text("status"));
      writer.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertEquals("BLOCKED", team.group(activity).text("accessMode"));
      System.out.printf(
          Locale.ROOT,
          "P2-1 message report shared lock reportPid=%d blockedWriterPid=%d%n",
          reportPid.get(),
          writerPid.get());
    } finally {
      releaseReport.countDown();
      closePool(pool);
    }
  }

  @Test
  @Order(11)
  void t11MembersShareLockBlocksSameActivityCancellation() throws Exception {
    UUID activity = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, activity, apply(BOB, activity));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch readerHolding = new CountDownLatch(1),
        writerStarted = new CountDownLatch(1),
        releaseReader = new CountDownLatch(1);
    AtomicInteger readerPid = new AtomicInteger(), writerPid = new AtomicInteger();
    try {
      Future<List<Row>> reader =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        readerPid.set(backendPid());
                        List<Row> rows = team.members(ALICE, activity);
                        readerHolding.countDown();
                        await(releaseReader, "release members reader");
                        return rows;
                      }));
      await(readerHolding, "members reader to hold the activity shared lock");
      Future<Row> writer =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        writerPid.set(backendPid());
                        writerStarted.countDown();
                        return activities.cancel(
                            ALICE,
                            activity,
                            Row.of(
                                "expectedVersion",
                                0,
                                "reason",
                                "members share-lock regression"));
                      }));
      await(writerStarted, "activity cancellation behind members reader to start");
      awaitBlockedBy(writerPid.get(), readerPid.get());

      releaseReader.countDown();
      assertEquals(2, reader.get(FUTURE_TIMEOUT_SECONDS, SECONDS).size());
      assertEquals("CANCELLED", writer.get(FUTURE_TIMEOUT_SECONDS, SECONDS).text("status"));
      System.out.printf(
          Locale.ROOT,
          "P2-1 T11 members readerPid=%d blockedCancellationPid=%d%n",
          readerPid.get(),
          writerPid.get());
    } finally {
      releaseReader.countDown();
      closePool(pool);
    }
  }

  private <T> T transaction(Supplier<T> action) {
    return new TransactionTemplate(transactions).execute(status -> action.get());
  }

  private Row member(List<Row> members, UUID user) {
    return members.stream()
        .filter(member -> user.equals(member.id("id")))
        .findFirst()
        .orElseThrow();
  }

  private int backendPid() {
    return Objects.requireNonNull(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
  }

  private void awaitBlockedBy(int waiterPid, int blockerPid) {
    long deadline = System.nanoTime() + OBSERVATION_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (isBlockedBy(waiterPid, blockerPid)) return;
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
    }
    fail("backend " + waiterPid + " was not observed waiting for backend " + blockerPid);
  }

  private boolean isBlockedBy(int waiterPid, int blockerPid) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists (select 1 from unnest(pg_blocking_pids(?)) as blocker(pid) where"
                + " blocker.pid=?)",
            Boolean.class,
            waiterPid,
            blockerPid));
  }

  private void assertNoBlockers(int backendPid) {
    Integer count =
        jdbc.queryForObject("select cardinality(pg_blocking_pids(?))", Integer.class, backendPid);
    assertEquals(0, Objects.requireNonNull(count));
  }

  private String sqlState(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
    return null;
  }

  private void await(CountDownLatch latch, String description) {
    try {
      assertTrue(latch.await(FUTURE_TIMEOUT_SECONDS, SECONDS), "timed out waiting to " + description);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting to " + description, error);
    }
  }

  private void closePool(ExecutorService pool) {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(FUTURE_TIMEOUT_SECONDS, SECONDS)) {
        pool.shutdownNow();
        assertTrue(
            pool.awaitTermination(FUTURE_TIMEOUT_SECONDS, SECONDS),
            "concurrency test worker pool did not terminate");
      }
    } catch (InterruptedException error) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while closing concurrency test worker pool", error);
    }
  }

  private static final class ForcedRollback extends RuntimeException {}
}
