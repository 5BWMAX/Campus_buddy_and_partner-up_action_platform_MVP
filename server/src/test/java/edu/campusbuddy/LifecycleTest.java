package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class LifecycleTest extends PgIntegrationSupport {
  @Test
  void leaveKeepsSeatExitRevokesGroupImmediatelyAndRejoinReusesGroup() {
    UUID a = activity(ALICE, 2, 7200, 10800);
    Row app = apply(BOB, a);
    UUID g = approve(ALICE, a, app).id("groupId");
    team.confirm(BOB, a, Row.of("status", "LEAVE", "expectedVersion", part(a, BOB).num("version")));
    assertEquals(2, team.occupied(a));
    problem("CAPACITY_FULL", () -> apply(CARA, a));
    chat.send(BOB, g, Row.of("clientMessageId", UUID.randomUUID(), "body", "收到"));
    long messages = sql.count("select count(*) from message");
    team.exit(BOB, a, Row.of("reason", "另有安排"), UUID.randomUUID());
    assertEquals(1, team.occupied(a));
    assertEquals(404, problem("RESOURCE_NOT_FOUND", () -> chat.messages(BOB, g, null, 20)).status);
    assertEquals(
        404,
        problem(
                "RESOURCE_NOT_FOUND",
                () ->
                    chat.send(BOB, g, Row.of("clientMessageId", UUID.randomUUID(), "body", "越权消息")))
            .status);
    assertEquals(messages, sql.count("select count(*) from message"));
    Row next = apply(BOB, a);
    assertEquals(2, next.num("round"));
    assertEquals(g, approve(ALICE, a, next).id("groupId"));
    assertEquals(1, sql.count("select count(*) from temporary_group where activity_id=?", a));
    problem(
        "OWNER_EXIT_REQUIRES_CANCEL",
        () -> team.exit(ALICE, a, Row.of("reason", "离开"), UUID.randomUUID()));
  }

  @Test
  void exactDeadlineRejectsEvenWithoutSchedulerAndExpirationIsTraceable() {
    UUID a = activity(ALICE, 3, 7200, 10800);
    Row app = apply(BOB, a);
    clock.set(activities.find(a).time("deadline"));
    problem("DEADLINE_PASSED", () -> approve(ALICE, a, app));
    problem("DEADLINE_PASSED", () -> apply(CARA, a));
    assertEquals(
        "PENDING",
        sql.must("select status from application where id=?", app.id("id")).text("status"));
    activities.advance(a);
    long n = sql.count("select count(*) from notification");
    activities.advance(a);
    assertEquals(n, sql.count("select count(*) from notification"));
    assertEquals(
        "EXPIRED",
        sql.must("select status from application where id=?", app.id("id")).text("status"));
    clock.set(activities.find(a).time("startAt"));
    activities.advance(a);
    long afterStart = sql.count("select count(*) from notification");
    activities.advance(a);
    assertEquals(
        afterStart,
        sql.count("select count(*) from notification"),
        "a state version transition must not reissue the deadline event");
  }

  @Test
  void editChecksEveryMemberConflictAndRollsBack() {
    UUID a = activity(ALICE, 4, 7200, 10800), b = activity(CARA, 4, 12000, 14400);
    approve(ALICE, a, apply(BOB, a));
    approve(CARA, b, apply(BOB, b));
    Row changed =
        body("修改时间", 4, clock.instant().plusSeconds(12500), clock.instant().plusSeconds(14000));
    changed.put("expectedVersion", 0);
    problem("TIME_CONFLICT", () -> activities.change(ALICE, a, changed));
    assertEquals(0, activities.find(a).num("version"));
    assertEquals(0, sql.count("select count(*) from activity_change where activity_id=?", a));
    assertEquals(2, team.occupied(a));
  }

  @Test
  void completionSnapshotIncludesOwnerOnceAndWindowEndsExactlyAt24Hours() {
    UUID a = activity(ALICE, 4, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    approve(ALICE, a, apply(CARA, a));
    clock.set(activities.find(a).time("endAt"));
    activities.advance(a);
    activities.advance(a);
    assertEquals(3, sql.count("select count(*) from completion_snapshot where activity_id=?", a));
    assertEquals(
        1,
        sql.count(
            "select count(*) from completion_snapshot where activity_id=? and user_id=? and"
                + " role='OWNER'",
            a,
            ALICE));
    Row answer =
        Row.of(
            "completion",
            "COMPLETED",
            "experience",
            5,
            "ratings",
            List.of(Row.of("targetUserId", BOB, "score", 1, "comment", "迟到")));
    feedback.submit(ALICE, a, answer);
    problem("ALREADY_SUBMITTED", () -> feedback.submit(ALICE, a, answer));
    assertEquals(0, sql.count("select count(*) from report"));
    clock.plus(86399);
    feedback.submit(BOB, a, Row.of("completion", "PARTIAL", "experience", 3, "reason", "没有完成全部任务"));
    clock.plus(1);
    problem(
        "FEEDBACK_CLOSED",
        () -> feedback.submit(CARA, a, Row.of("completion", "COMPLETED", "experience", 4)));
    assertEquals(2, sql.count("select count(*) from feedback where activity_id=?", a));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "update completion_snapshot set role='MEMBER' where activity_id=? and user_id=?",
                a,
                ALICE));
  }

  @Test
  void outsiderCannotBeRatedAndCancelledArchiveCannotCreateFeedback() {
    UUID a = activity(ALICE, 4, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    clock.set(activities.find(a).time("endAt"));
    activities.advance(a);
    problem(
        "INVALID_RATING_TARGET",
        () ->
            feedback.submit(
                ALICE,
                a,
                Row.of(
                    "completion",
                    "COMPLETED",
                    "experience",
                    5,
                    "ratings",
                    List.of(Row.of("targetUserId", DAN, "score", 1)))));
    assertEquals(0, sql.count("select count(*) from feedback"));
    UUID b = activity(CARA, 2, 7200, 10800);
    activities.cancel(CARA, b, Row.of("expectedVersion", 0, "reason", "取消"));
    activities.archive(CARA, b, Row.of("expectedVersion", 1));
    assertEquals("CANCELLED", activities.find(b).text("terminalState"));
    problem("RESOURCE_NOT_FOUND", () -> feedback.eligible(CARA, b));
    assertEquals(0, sql.count("select count(*) from completion_snapshot where activity_id=?", b));
  }

  @Test
  void reapplicationHasNewRoundAndOldApprovalFails() {
    UUID a = activity(ALICE, 3, 7200, 10800);
    Row first = apply(BOB, a);
    problem("DUPLICATE_APPLICATION", () -> apply(BOB, a));
    team.resolve(BOB, a, first.id("id"), approval(first), true);
    Row second = apply(BOB, a);
    assertEquals(2, second.num("round"));
    problem("ROUND_MISMATCH", () -> approve(ALICE, a, first));
    assertEquals(
        3,
        sql.count(
            "select count(*) from application_history where application_id=?", first.id("id")));
    approve(ALICE, a, second);
    assertEquals(2, team.occupied(a));
  }

  @Test
  void importantChangeResetsConfirmationNotMembershipAndCheckinIsIdempotent() {
    UUID a = activity(ALICE, 4, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    apply(CARA, a);
    team.confirm(BOB, a, Row.of("status", "CONFIRMED", "expectedVersion", 0));
    activities.change(ALICE, a, Row.of("expectedVersion", 0, "publicLocation", "新的集合地点"));
    assertEquals("UNCONFIRMED", part(a, BOB).text("confirmation"));
    assertEquals(2, team.occupied(a));
    assertEquals(3, sql.count("select count(*) from notification where type='ACTIVITY_CHANGED'"));
    clock.set(activities.find(a).time("startAt"));
    Row first = team.checkin(BOB, a, Row.of("status", "SELF_REPORTED"));
    Row second = team.checkin(BOB, a, Row.of("status", "SELF_REPORTED"));
    assertEquals(first, second);
    assertEquals(0, sql.count("select count(*) from report"));
  }

  @Test
  void removalIsIdempotentOwnerProtectedAndMemberLosesAccess() {
    UUID a = activity(ALICE, 3, 7200, 10800);
    UUID g = approve(ALICE, a, apply(BOB, a)).id("groupId");
    team.remove(ALICE, a, BOB, "违反约定", false);
    long n = sql.count("select count(*) from notification");
    team.remove(ALICE, a, BOB, "重复请求", false);
    assertEquals(1, team.occupied(a));
    assertEquals(n, sql.count("select count(*) from notification"));
    problem("RESOURCE_NOT_FOUND", () -> chat.messages(BOB, g, null, 20));
    problem("MEMBER_REMOVED", () -> apply(BOB, a));
    problem("OWNER_REMOVAL_REQUIRES_CANCEL", () -> team.remove(ALICE, a, ALICE, "测试", false));
  }
}
