package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GovernancePrivacyTest extends PgIntegrationSupport {
  UUID report(UUID reporter, String type, UUID target) {
    return governance
        .report(
            reporter,
            Row.of(
                "targetType", type, "targetId", target, "reason", "具体可核实的问题", "evidence", "模拟证据说明"))
        .id("reportId");
  }

  void processing(UUID id) {
    governance.phase(ADMIN, id, Row.of("expectedVersion", 0, "reason", "已受理"), true);
    governance.phase(ADMIN, id, Row.of("expectedVersion", 1, "reason", "正在核实"), false);
  }

  Row decision(String action) {
    return Row.of(
        "dispositionId",
        UUID.randomUUID(),
        "expectedVersion",
        2,
        "action",
        action,
        "reason",
        "核验证据后的决定",
        "evidenceReviewed",
        true);
  }

  @Test
  void privacyDefaultsAreHiddenEncryptionAndGroupRevocationWork() {
    identity.update(ALICE, Row.of("contact", "private-138-test"));
    UUID a = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    assertFalse(activities.find(a).text("exactLocationCiphertext").contains("三楼东侧"));
    assertFalse(
        sql.must("select contact_ciphertext from user_account where id=?", ALICE)
            .text("contactCiphertext")
            .contains("private-138-test"));
    assertFalse(activities.details(BOB, a).containsKey("exactLocation"));
    assertFalse(team.members(BOB, a).getFirst().containsKey("contact"));
    identity.privacy(ALICE, Row.of("contactVisibility", "GROUP", "locationVisibility", "GROUP"));
    assertEquals("三楼东侧", activities.details(BOB, a).text("exactLocation"));
    assertTrue(
        team.members(BOB, a).stream().anyMatch(x -> "private-138-test".equals(x.get("contact"))));
    assertFalse(activities.details(CARA, a).containsKey("exactLocation"));
    team.exit(BOB, a, Row.of("reason", "退出"), UUID.randomUUID());
    assertFalse(activities.details(BOB, a).containsKey("exactLocation"));
    problem("RESOURCE_NOT_FOUND", () -> team.members(BOB, a));
  }

  @Test
  void fullGovernanceLifecycleRequiresReasonsEvidenceVersionAndAllowsDisabledAppeal() {
    UUID id = report(ALICE, "USER", BOB);
    problem("FORBIDDEN", () -> governance.list(ALICE, true));
    problem(
        "VALIDATION_FAILED",
        () -> governance.phase(ADMIN, id, Row.of("expectedVersion", 0, "reason", " "), true));
    processing(id);
    Row b = decision("DISABLE_USER");
    b.put("evidenceReviewed", false);
    problem("EVIDENCE_REQUIRED", () -> governance.decide(ADMIN, id, b));
    assertEquals(
        0,
        sql.count("select count(*) from idempotency_record where operation like 'disposition:%'"));
    b.put("evidenceReviewed", true);
    Row first = governance.decide(ADMIN, id, b);
    assertEquals("RESOLVED", first.text("status"));
    assertEquals("DISABLED", identity.profile(BOB).text("status"));
    long audits = sql.count("select count(*) from audit_log"),
        notices = sql.count("select count(*) from notification");
    assertEquals(json.write(first), json.write(governance.decide(ADMIN, id, b)));
    assertEquals(audits, sql.count("select count(*) from audit_log"));
    assertEquals(notices, sql.count("select count(*) from notification"));
    Row altered = new Row(b);
    altered.put("reason", "不同决定");
    problem("KEY_REUSED", () -> governance.decide(ADMIN, id, altered));
    problem("ACCOUNT_DISABLED", () -> apply(BOB, activity(CARA, 3, 7200, 10800)));
    assertEquals(1, governance.list(BOB, false).size());
    UUID appeal =
        governance.appeal(BOB, id, Row.of("reason", "补充说明", "evidence", "核查后的证据")).id("appealId");
    problem("FORBIDDEN", () -> governance.appeal(DAN, id, Row.of("reason", "无关当事人")));
    governance.review(
        ADMIN, appeal, Row.of("expectedVersion", 0, "result", "REVOKED", "reason", "证据不足，恢复账号"));
    assertEquals("NORMAL", identity.profile(BOB).text("status"));
    assertEquals("APPEAL_RESOLVED", governance.list(ALICE, false).getFirst().text("status"));
    assertEquals(5, sql.count("select count(*) from audit_log"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("update audit_log set reason='篡改'"));
  }

  @Test
  void slaIsDetectableAndStatisticsUseFixedDenominators() {
    Row empty =
        governance.statistics(
            ADMIN, Row.of("from", "2000-01-01T00:00:00Z", "to", "2040-01-01T00:00:00Z"));
    assertNull(empty.get("completionRate"));
    assertNull(empty.get("applicationConversion"));
    assertNull(empty.get("noShowRate"));
    UUID a = activity(ALICE, 3, 7200, 10800), cancel = activity(CARA, 2, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    activities.cancel(CARA, cancel, Row.of("expectedVersion", 0, "reason", "取消测试"));
    UUID report = report(ALICE, "USER", BOB);
    clock.plus(10801);
    activities.advance(a);
    // Database creation timestamps are deliberately moved for a deterministic SLA boundary.
    jdbc.update(
        "update report set created_at=? where id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(86400)),
        report);
    assertTrue(governance.list(ADMIN, true).getFirst().bool("overdue"));
    processing(report);
    Row b = decision("CONFIRM_NO_SHOW");
    b.put("activityId", a);
    governance.decide(ADMIN, report, b);
    Row stats =
        governance.statistics(
            ADMIN, Row.of("from", "2000-01-01T00:00:00Z", "to", "2040-01-01T00:00:00Z"));
    assertEquals(2, stats.num("dueActivities"));
    assertEquals(1, stats.num("cancelledActivities"));
    assertEquals(0.5, stats.get("completionRate"));
    assertEquals(2, stats.num("snapshotMembers"));
    assertEquals(1, stats.num("confirmedNoShows"));
    assertEquals(0.5, stats.get("noShowRate"));
    assertEquals(1, stats.num("lateReports"));
    assertFalse(json.write(stats).contains("nickname"));
  }

  @Test
  void noShowRequiresSnapshotAndDissolutionBlocksGroupWithoutDuplicateEffects() {
    UUID a = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    UUID group = team.group(a).id("id");
    UUID id = report(BOB, "ACTIVITY", a);
    processing(id);
    Row b = decision("DISSOLVE_GROUP");
    governance.decide(ADMIN, id, b);
    problem("RESOURCE_NOT_FOUND", () -> chat.messages(BOB, group, null, 20));
    governance.decide(ADMIN, id, b);
    assertEquals(2, team.occupied(a));
    clock.plus(10801);
    activities.advance(a);
    UUID invalid = report(ALICE, "USER", CARA);
    processing(invalid);
    Row f = decision("CONFIRM_NO_SHOW");
    f.put("activityId", a);
    problem("INVALID_FINDING_TARGET", () -> governance.decide(ADMIN, invalid, f));
  }
}
