package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class ApprovalTest extends PgIntegrationSupport {
  @Test
  void concurrentLastSeatHasOneWinner() throws Exception {
    UUID a = activity(ALICE, 2, 7200, 10800);
    Row b = apply(BOB, a), c = apply(CARA, a);
    assertEquals(1, team.occupied(a));
    assertNull(team.group(a));
    oneWinner(race(() -> approve(ALICE, a, b), () -> approve(ALICE, a, c)), "CAPACITY_FULL");
    assertEquals(2, team.occupied(a));
    assertEquals(1, sql.count("select count(*) from temporary_group where activity_id=?", a));
    assertEquals(
        1,
        sql.count("select count(*) from application where activity_id=? and status='APPROVED'", a));
    assertEquals(
        1,
        sql.count("select count(*) from application where activity_id=? and status='PENDING'", a));
    assertEquals(
        2, sql.count("select count(*) from notification where type='APPLICATION_APPROVED'"));
  }

  @Test
  void concurrentOverlappingActivitiesSerializeOnApplicant() throws Exception {
    UUID a = activity(ALICE, 3, 7200, 10800), b = activity(CARA, 3, 8000, 12000);
    Row x = apply(BOB, a), y = apply(BOB, b);
    oneWinner(race(() -> approve(ALICE, a, x), () -> approve(CARA, b, y)), "TIME_CONFLICT");
    assertEquals(
        1,
        sql.count("select count(*) from participation where user_id=? and status='ACTIVE'", BOB));
  }

  @Test
  void adjacentHalfOpenIntervalsAreAllowed() {
    UUID a = activity(ALICE, 3, 7200, 10800), b = activity(CARA, 3, 10800, 14400);
    approve(ALICE, a, apply(BOB, a));
    approve(CARA, b, apply(BOB, b));
    assertEquals(
        2,
        sql.count("select count(*) from participation where user_id=? and status='ACTIVE'", BOB));
  }

  @Test
  void successfulRequestReplaysWithoutExtraWritesAndRejectsChangedContent() {
    UUID a = activity(ALICE, 3, 7200, 10800);
    Row app = apply(BOB, a);
    UUID key = UUID.randomUUID();
    Row body = approval(app);
    Row first = team.approve(ALICE, a, app.id("id"), body, key);
    long notices = sql.count("select count(*) from notification");
    Row replay = team.approve(ALICE, a, app.id("id"), body, key);
    assertEquals(json.write(first), json.write(replay));
    assertEquals(notices, sql.count("select count(*) from notification"));
    assertEquals(2, team.occupied(a));
    Row changed = new Row(body);
    changed.put("round", 2);
    problem("KEY_REUSED", () -> team.approve(ALICE, a, app.id("id"), changed, key));
    problem("FORBIDDEN", () -> team.approve(CARA, a, app.id("id"), body, key));
  }

  @Test
  void notificationInsertFailureRollsBackEveryApprovalWriteAndAllowsSameKeyRetry() {
    UUID a = activity(ALICE, 3, 7200, 10800);
    Row app = apply(BOB, a);
    UUID key = UUID.randomUUID();
    long history = sql.count("select count(*) from application_history"),
        notices = sql.count("select count(*) from notification"),
        tasks = sql.count("select count(*) from delivery_task"),
        keys = sql.count("select count(*) from idempotency_record");
    jdbc.execute(
        "create function injected_notification_failure() returns trigger language plpgsql as $$"
            + " begin if NEW.type='APPLICATION_APPROVED' then raise exception 'deliberate test"
            + " fault'; end if; return NEW; end; $$");
    jdbc.execute(
        "create trigger injected_notification_failure before insert on notification for each row"
            + " execute function injected_notification_failure()");
    try {
      assertThrows(
          DataAccessException.class,
          () -> team.approve(ALICE, a, app.id("id"), approval(app), key));
    } finally {
      jdbc.execute("drop trigger injected_notification_failure on notification");
      jdbc.execute("drop function injected_notification_failure()");
    }
    assertEquals(
        "PENDING",
        sql.must("select status from application where id=?", app.id("id")).text("status"));
    assertEquals(1, team.occupied(a));
    assertNull(team.group(a));
    assertEquals(history, sql.count("select count(*) from application_history"));
    assertEquals(notices, sql.count("select count(*) from notification"));
    assertEquals(tasks, sql.count("select count(*) from delivery_task"));
    assertEquals(keys, sql.count("select count(*) from idempotency_record"));
    team.approve(ALICE, a, app.id("id"), approval(app), key);
    assertEquals(2, team.occupied(a));
  }
}
