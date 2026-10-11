package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DeliveryTest extends PgIntegrationSupport {
  @Test
  void failedPushKeepsBusinessAndExpiredLeaseCannotOverwriteNewOwner() {
    UUID a = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    long notices = sql.count("select count(*) from notification");
    push.setUnavailable(true);
    worker.runOnce();
    assertEquals(2, team.occupied(a));
    assertEquals(notices, sql.count("select count(*) from notification"));
    assertEquals(
        notices,
        sql.count("select count(*) from delivery_task where status='RETRY' and attempts=1"));
    assertTrue(worker.claim().isEmpty());
    clock.plus(6);
    List<Row> first = worker.claim();
    assertFalse(first.isEmpty());
    Row stale = first.getFirst();
    clock.plus(61);
    List<Row> next = worker.claim();
    Row fresh =
        next.stream().filter(x -> x.id("id").equals(stale.id("id"))).findFirst().orElseThrow();
    assertNotEquals(stale.id("leaseToken"), fresh.id("leaseToken"));
    worker.finish(stale, true, 1);
    assertEquals(
        "RUNNING",
        sql.must("select status from delivery_task where id=?", stale.id("id")).text("status"));
    worker.finish(fresh, true, 1);
    assertEquals(
        "ACCEPTED",
        sql.must("select status from delivery_task where id=?", stale.id("id")).text("status"));
    assertEquals(
        3, sql.count("select count(*) from delivery_attempt where task_id=?", stale.id("id")));
    assertEquals(
        1,
        sql.count(
            "select count(*) from delivery_attempt where task_id=? and result='LEASE_EXPIRED'",
            stale.id("id")));
  }

  @Test
  void unknownEventVersionIsQuarantinedWithoutSending() {
    activity(ALICE, 2, 7200, 10800);
    jdbc.update("update delivery_task set schema_version=99");
    assertTrue(worker.claim().isEmpty());
    assertEquals(1, sql.count("select count(*) from delivery_task where status='QUARANTINED'"));
    assertEquals(0, sql.count("select count(*) from delivery_attempt"));
  }

  @Test
  void cancelledActivityMakesQueuedReminderObsolete() {
    UUID a = activity(ALICE, 2, 7200, 10800);
    clock.plus(5401);
    activities.advance(a);
    assertEquals(1, sql.count("select count(*) from notification where type='START_REMINDER'"));
    activities.cancel(ALICE, a, Row.of("expectedVersion", 0, "reason", "计划取消"));
    worker.runOnce();
    assertEquals(1, sql.count("select count(*) from delivery_task where status='OBSOLETE'"));
    assertEquals(1, sql.count("select count(*) from notification where type='START_REMINDER'"));
  }

  @Test
  void notificationReadIsIdempotentAndCannotReadOtherUsers() {
    activity(ALICE, 2, 7200, 10800);
    Row page = notifications.list(ALICE, false, null, 20);
    assertEquals(1, page.num("unreadCount"));
    Row n = new Row((Map<String, Object>) ((List<?>) page.get("items")).getFirst());
    problem("RESOURCE_NOT_FOUND", () -> notifications.read(BOB, n.id("id")));
    Row one = notifications.read(ALICE, n.id("id"));
    clock.plus(5);
    assertEquals(one, notifications.read(ALICE, n.id("id")));
    assertEquals(0, notifications.list(ALICE, false, null, 20).num("unreadCount"));
    assertTrue(((List<?>) notifications.list(BOB, false, null, 20).get("items")).isEmpty());
  }

  @Test
  void channelTimeoutDoesNotHoldBusinessTransactions() {
    activity(ALICE, 2, 7200, 10800);
    push.setDelayMillis(10000);
    long start = System.nanoTime();
    try {
      worker.runOnce();
    } finally {
      push.setDelayMillis(0);
    }
    long millis = (System.nanoTime() - start) / 1000000;
    assertTrue(millis >= 2800 && millis < 5000, "observed timeout " + millis);
    assertEquals(1, sql.count("select count(*) from delivery_task where status='RETRY'"));
    assertEquals(1, sql.count("select count(*) from activity"));
  }

  @Test
  void reminderIsCheckedAgainAfterClaim() {
    UUID a = activity(ALICE, 2, 7200, 10800);
    clock.plus(5401);
    activities.advance(a);
    Row reminder =
        worker.claim().stream()
            .filter(t -> new Row((Map<String, Object>) t.get("payload")).bool("reminder"))
            .findFirst()
            .orElseThrow();
    activities.cancel(ALICE, a, Row.of("expectedVersion", 0, "reason", "取消"));
    assertFalse(worker.beforeSend(reminder));
    assertEquals(
        "OBSOLETE",
        sql.must("select status from delivery_task where id=?", reminder.id("id")).text("status"));
  }

  @Test
  void thirtiethFailureAndExpiredLeaseAreTerminal() {
    activity(ALICE, 2, 7200, 10800);
    jdbc.update("update delivery_task set attempts=29");
    Row last = worker.claim().getFirst();
    assertEquals(30, last.num("attempts"));
    worker.finish(last, false, 3);
    assertTrue(worker.claim().isEmpty());
    assertEquals(
        "FAILED",
        sql.must("select status from delivery_task where id=?", last.id("id")).text("status"));
    activity(BOB, 2, 7200, 10800);
    jdbc.update("update delivery_task set attempts=29 where status='READY'");
    Row expired = worker.claim().getFirst();
    clock.plus(61);
    assertTrue(worker.claim().isEmpty());
    assertEquals(
        "FAILED",
        sql.must("select status from delivery_task where id=?", expired.id("id")).text("status"));
  }
}
