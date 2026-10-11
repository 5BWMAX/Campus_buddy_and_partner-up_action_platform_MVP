package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ScheduledBoundaryTest extends PgIntegrationSupport {
  @Autowired PlatformTransactionManager transactions;

  @Test
  void pendingDeadlineAndStartRemindersHaveExactBoundariesAndNoDuplicates() {
    UUID a = activity(ALICE, 4, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    Row pending = apply(CARA, a);
    assertFalse(activities.due().contains(a));
    Instant deadline = activities.find(a).time("deadline"),
        start = activities.find(a).time("startAt");
    clock.set(deadline.minusSeconds(601));
    activities.advance(a);
    assertEquals(0, sql.count("select count(*) from notification where type='PENDING_REMINDER'"));
    clock.plus(1);
    activities.advance(a);
    activities.advance(a);
    assertEquals(
        1,
        sql.count(
            "select count(*) from notification where type='PENDING_REMINDER' and recipient_id=?",
            ALICE));
    assertEquals(0, sql.count("select count(*) from notification where type='START_REMINDER'"));
    clock.set(start.minusSeconds(1800));
    activities.advance(a);
    activities.advance(a);
    assertEquals(2, sql.count("select count(*) from notification where type='START_REMINDER'"));
    assertEquals(2, sql.count("select count(*) from notification where type='DEADLINE_REACHED'"));
    assertEquals(
        1,
        sql.count(
            "select count(*) from notification where type='APPLICATION_EXPIRED' and recipient_id=?",
            CARA));
    assertEquals(
        "EXPIRED",
        sql.must("select status from application where id=?", pending.id("id")).text("status"));
    assertEquals(
        1,
        sql.count(
            "select count(*) from application_history where application_id=? and"
                + " to_status='EXPIRED'",
            pending.id("id")));
    long count = sql.count("select count(*) from notification");
    clock.set(start);
    activities.advance(a);
    activities.advance(a);
    assertEquals("IN_PROGRESS", activities.find(a).text("status"));
    assertEquals(count, sql.count("select count(*) from notification"));
  }

  @Test
  void sameTimestampMessagePagesNeitherDuplicateNorLoseRowsWhenNewMessagesArrive() {
    UUID a = activity(ALICE, 3, 7200, 10800), g = approve(ALICE, a, apply(BOB, a)).id("groupId");
    Set<UUID> original = new HashSet<>();
    for (int i = 0; i < 67; i++)
      original.add(
          chat.send(BOB, g, Row.of("clientMessageId", UUID.randomUUID(), "body", "message " + i))
              .id("id"));
    jdbc.update(
        "update message set created_at=? where group_id=?",
        java.sql.Timestamp.from(clock.instant()),
        g);
    Set<UUID> found = new HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      Row page = chat.messages(ALICE, g, cursor, 13);
      for (Object raw : (List<?>) page.get("items"))
        assertTrue(
            found.add(new Row((Map<String, Object>) raw).id("id")), "duplicate across pages");
      cursor = (String) page.get("nextCursor");
      if (pages++ == 0) {
        Row latest =
            chat.send(
                BOB,
                g,
                Row.of("clientMessageId", UUID.randomUUID(), "body", "arrived after first page"));
        jdbc.update(
            "update message set created_at=? where id=?",
            java.sql.Timestamp.from(clock.instant().plusSeconds(1)),
            latest.id("id"));
      }
      assertTrue(pages < 10, "cursor must progress");
    } while (cursor != null);
    assertEquals(original, found);
    assertEquals(6, pages);
    Row newest =
        new Row(
            (Map<String, Object>)
                ((List<?>) chat.messages(ALICE, g, null, 13).get("items")).getFirst());
    assertEquals("arrived after first page", newest.text("body"));
  }

  @Test
  void snapshotWaitsForCommittedExitAndRejectsExitAfterEndWins() throws Exception {
    UUID a = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    CountDownLatch exited = new CountDownLatch(1),
        commit = new CountDownLatch(1),
        ending = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> exit =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            Row out =
                                team.exit(
                                    BOB, a, Row.of("reason", "before deadline"), UUID.randomUUID());
                            exited.countDown();
                            try {
                              if (!commit.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("commit barrier timed out");
                            } catch (InterruptedException e) {
                              throw new RuntimeException(e);
                            }
                            return out;
                          }));
      assertTrue(exited.await(5, TimeUnit.SECONDS));
      clock.set(activities.find(a).time("endAt"));
      Future<?> end =
          pool.submit(
              () -> {
                ending.countDown();
                activities.advance(a);
              });
      assertTrue(ending.await(5, TimeUnit.SECONDS));
      commit.countDown();
      exit.get(10, TimeUnit.SECONDS);
      end.get(10, TimeUnit.SECONDS);
      assertEquals("ENDED", activities.find(a).text("status"));
      assertEquals(
          0,
          sql.count(
              "select count(*) from completion_snapshot where activity_id=? and user_id=?",
              a,
              BOB));
      assertEquals(1, sql.count("select count(*) from completion_snapshot where activity_id=?", a));
    } finally {
      commit.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
    UUID b = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, b, apply(BOB, b));
    clock.set(activities.find(b).time("endAt"));
    activities.advance(b);
    problem(
        "CONFIRMATION_CLOSED",
        () -> team.exit(BOB, b, Row.of("reason", "too late"), UUID.randomUUID()));
    assertEquals(2, sql.count("select count(*) from completion_snapshot where activity_id=?", b));
  }

  @Test
  void overdueReportIsPersistedAndAlertsAdministratorsOnceAcrossConcurrentScans() throws Exception {
    UUID report =
        governance
            .report(BOB, Row.of("targetType", "USER", "targetId", ALICE, "reason", "needs review"))
            .id("reportId");
    jdbc.update(
        "update report set created_at=? where id=?",
        java.sql.Timestamp.from(clock.instant()),
        report);
    clock.plus(86399);
    assertEquals(0, governance.scanOverdue());
    clock.plus(1);
    List<Object> runs =
        race(
            () -> Row.of("count", governance.scanOverdue()),
            () -> Row.of("count", governance.scanOverdue()));
    assertTrue(runs.stream().allMatch(Row.class::isInstance), runs.toString());
    assertEquals(1, runs.stream().map(Row.class::cast).mapToLong(x -> x.num("count")).sum());
    assertEquals(
        1, sql.count("select count(*) from governance_sla_event where report_id=?", report));
    assertEquals(
        1,
        sql.count(
            "select count(*) from notification where type='REPORT_SLA_OVERDUE' and recipient_id=?",
            ADMIN));
    assertEquals(0, governance.scanOverdue());
    governance.phase(
        ADMIN, report, Row.of("expectedVersion", 0, "reason", "late acceptance"), true);
    assertEquals(0, governance.scanOverdue());
    assertTrue(governance.list(ADMIN, true).getFirst().bool("overdue"));
  }

  @Test
  void revokingRestrictionNeverResurrectsErasedIdentity() {
    identity.erase(BOB);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            s -> {
              identity.disable(BOB, false);
              identity.disable(BOB, true);
            });
    Row user = sql.must("select * from user_account where id=?", BOB);
    assertEquals("DELETED", user.text("status"));
    assertNull(user.get("campusSubject"));
    assertNull(user.get("contactCiphertext"));
    assertFalse(identity.exists(BOB));
    assertEquals(1, sql.count("select count(*) from erasure_log where user_id=?", BOB));
  }

  @Test
  void oneHundredCommittedMockChannelTasksAreAcceptedWithinFiveMinutes() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            s -> {
              for (int i = 0; i < 100; i++)
                notifications.record(
                    BOB, null, "PROBE", "mock-accept-" + i, Row.of("message", "delivery probe"));
            });
    assertEquals(100, sql.count("select count(*) from delivery_task"));
    long start = System.nanoTime();
    for (int i = 0; i < 5; i++) worker.runOnce();
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofMinutes(5)) < 0);
    assertEquals(
        100,
        sql.count("select count(*) from delivery_task where status='ACCEPTED' and attempts=1"));
    assertEquals(100, sql.count("select count(*) from delivery_attempt where result='ACCEPTED'"));
  }
}
