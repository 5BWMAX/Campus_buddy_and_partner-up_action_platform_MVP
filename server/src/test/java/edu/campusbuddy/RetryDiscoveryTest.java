package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RetryDiscoveryTest extends PgIntegrationSupport {
  void inject(int failures) {
    jdbc.execute("create sequence retry_test_sequence");
    jdbc.execute(
        "create function retry_test_failure() returns trigger language plpgsql as $$ begin if"
            + " nextval('retry_test_sequence')<="
            + failures
            + " then raise exception 'injected serialization failure' using errcode='40001'; end"
            + " if; return new; end $$");
    jdbc.execute(
        "create trigger retry_test_failure before insert on notification for each row execute"
            + " function retry_test_failure()");
  }

  void cleanup() {
    jdbc.execute("drop trigger if exists retry_test_failure on notification");
    jdbc.execute("drop function if exists retry_test_failure()");
    jdbc.execute("drop sequence if exists retry_test_sequence");
  }

  @Test
  void transientFailureRestartsWholeTransactionTwiceAndCommitsOnce() {
    try {
      inject(2);
      UUID a = activity(ALICE, 2, 7200, 10800);
      assertNotNull(a);
      assertEquals(3, sql.count("select last_value from retry_test_sequence"));
      assertEquals(1, sql.count("select count(*) from activity"));
      assertEquals(1, sql.count("select count(*) from participation"));
      assertEquals(1, sql.count("select count(*) from notification"));
      assertEquals(1, sql.count("select count(*) from delivery_task"));
      assertEquals(1, sql.count("select count(*) from idempotency_record"));
    } finally {
      cleanup();
    }
  }

  @Test
  void thirdFailurePropagatesAndLeavesNoBusinessOrIdempotency() {
    try {
      inject(99);
      assertThrows(
          org.springframework.dao.DataAccessException.class, () -> activity(ALICE, 2, 7200, 10800));
      assertEquals(3, sql.count("select last_value from retry_test_sequence"));
      assertEquals(0, sql.count("select count(*) from activity"));
      assertEquals(0, sql.count("select count(*) from notification"));
      assertEquals(0, sql.count("select count(*) from idempotency_record"));
    } finally {
      cleanup();
    }
  }

  @Test
  void discoveryFiltersPaginationAndLocationConsentAreValidated() {
    for (int i = 0; i < 3; i++) activity(ALICE, 2, 7200 + i * 7200, 10800 + i * 7200);
    Row first = activities.search(Row.of("type", "STUDY", "pageSize", 1), false);
    assertEquals(1, ((List<?>) first.get("items")).size());
    String token = first.text("nextCursor");
    assertFalse(token.isBlank());
    Row next = activities.search(Row.of("type", "STUDY", "pageSize", 1, "cursor", token), false);
    assertNotEquals(json.write(first.get("items")), json.write(next.get("items")));
    problem(
        "CURSOR_INVALID", () -> activities.search(Row.of("type", "SPORT", "cursor", token), false));
    problem(
        "LOCATION_PERMISSION_REQUIRED",
        () -> activities.search(Row.of("sort", "DISTANCE", "lat", 31.2, "lon", 121.4), true));
    assertEquals(
        3,
        ((List<?>)
                activities
                    .search(
                        Row.of(
                            "sort", "DISTANCE", "locationConsent", true, "lat", 31.2, "lon", 121.4),
                        true)
                    .get("items"))
            .size());
    assertTrue(((List<?>) activities.search(Row.of("tag", "不存在"), false).get("items")).isEmpty());
  }

  @Test
  void editRacingWithApprovalNeverCreatesOverlappingParticipation() throws Exception {
    UUID a = activity(ALICE, 3, 7200, 10800), b = activity(BOB, 3, 20000, 23600);
    approve(BOB, b, apply(CARA, b));
    Row app = apply(CARA, a);
    Row edit =
        Row.of(
            "expectedVersion",
            0,
            "startAt",
            clock.instant().plusSeconds(20000).toString(),
            "endAt",
            clock.instant().plusSeconds(23600).toString(),
            "deadline",
            clock.instant().plusSeconds(18200).toString(),
            "confirmationDeadline",
            clock.instant().plusSeconds(20000).toString());
    oneWinner(
        race(() -> activities.change(ALICE, a, edit), () -> approve(ALICE, a, app)),
        "TIME_CONFLICT");
    long conflicts =
        sql.count(
            "select count(*) from participation p join activity a on a.id=p.activity_id join"
                + " participation q on q.user_id=p.user_id and q.activity_id>p.activity_id join"
                + " activity b on b.id=q.activity_id where p.status='ACTIVE' and q.status='ACTIVE'"
                + " and a.start_at<b.end_at and a.end_at>b.start_at");
    assertEquals(0, conflicts);
  }

  @Test
  void unknownOnlyOrEmptyChangeIsRejectedAndDoesNotIncrementVersion() {
    UUID a = activity(ALICE, 2, 7200, 10800);
    problem("VALIDATION_FAILED", () -> activities.change(ALICE, a, Row.of("expectedVersion", 0)));
    problem(
        "VALIDATION_FAILED",
        () -> activities.change(ALICE, a, Row.of("expectedVersion", 0, "unknown", 1)));
    assertEquals(0, activities.find(a).num("version"));
  }
}
