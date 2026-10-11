package edu.campusbuddy.governance.application;

import edu.campusbuddy.activity.api.*;
import edu.campusbuddy.chat.api.*;
import edu.campusbuddy.governance.infrastructure.*;
import edu.campusbuddy.identity.api.*;
import edu.campusbuddy.notification.api.*;
import edu.campusbuddy.shared.*;
import edu.campusbuddy.team.api.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class GovernanceService implements edu.campusbuddy.governance.api.GovernanceApi {
  private final GovernanceRepository repo;
  private final IdentityApi identity;
  private final ActivityApi activities;
  private final TeamApi team;
  private final ChatApi chat;
  private final NotificationApi notices;
  private final Clock clock;
  private final Idempotency idem;

  public GovernanceService(
      GovernanceRepository r,
      IdentityApi i,
      ActivityApi a,
      TeamApi t,
      ChatApi c,
      NotificationApi n,
      Clock cl,
      Idempotency idempotency) {
    repo = r;
    identity = i;
    activities = a;
    team = t;
    chat = c;
    notices = n;
    clock = cl;
    idem = idempotency;
  }

  @Transactional
  public int scanOverdue() {
    List<Row> events = repo.recordOverdue(clock.instant());
    for (Row event : events) {
      for (UUID admin : identity.administrators())
        notices.record(
            admin,
            null,
            "REPORT_SLA_OVERDUE",
            event.id("reportId") + ":sla24:" + admin,
            Row.of(
                "message",
                "举报已超过24小时受理期限，请立即处理",
                "reportId",
                event.id("reportId"),
                "deadlineAt",
                event.time("deadlineAt")));
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "reportSlaOverdue reportId={} deadlineAt={}",
              event.id("reportId"),
              event.time("deadlineAt"));
    }
    return events.size();
  }

  private UUID subject(Row r) {
    return switch (r.text("targetType")) {
      case "USER" -> r.id("targetId");
      case "ACTIVITY" -> activities.find(r.id("targetId")).id("ownerId");
      default -> chat.author(r.id("targetId"));
    };
  }

  private void related(UUID actor, Row r) {
    Problem.require(
        identity.exists(actor) && (actor.equals(r.id("reporterId")) || actor.equals(subject(r))),
        403,
        "FORBIDDEN",
        "仅当事人可申诉");
  }

  @Transactional
  public Row report(UUID actor, Map<String, Object> b) {
    identity.check(actor);
    String type = Input.choice(b, "targetType", "ACTIVITY", "USER", "MESSAGE");
    UUID target = Input.uuid(b.get("targetId"));
    switch (type) {
      case "ACTIVITY" -> activities.find(target);
      case "USER" -> Problem.require(identity.exists(target), 404, "RESOURCE_NOT_FOUND", "用户不存在");
      case "MESSAGE" -> chat.requireReportable(actor, target);
    }
    UUID id =
        repo.report(
            actor,
            type,
            target,
            Input.text(b, "reason", 1000, true),
            Input.text(b, "evidence", 2000, false));
    notices.record(
        actor,
        null,
        "REPORT_SUBMITTED",
        id + ":submitted",
        Row.of("message", "举报已提交，可在此查看处理与申诉进度"));
    return Row.of("reportId", id, "status", "SUBMITTED");
  }

  public List<Row> list(UUID actor, boolean admin) {
    if (admin) identity.requireAdmin(actor);
    else Problem.require(identity.exists(actor), 401, "AUTH_REQUIRED", "请登录");
    List<Row> rows = repo.reports(actor, admin);
    for (Row r : rows) {
      r.put("appeals", repo.appeals(r.id("id")));
      r.put(
          "overdue",
          (r.get("acceptedAt") == null ? clock.instant() : r.time("acceptedAt"))
                  .compareTo(r.time("createdAt").plusSeconds(86400))
              >= 0);
    }
    return rows;
  }

  @Transactional
  public Row phase(UUID actor, UUID id, Map<String, Object> b, boolean accept) {
    identity.requireAdmin(actor);
    Row before = repo.report(id, false);
    identity.lockUsers(List.of(actor, before.id("reporterId"), subject(before)));
    identity.requireAdmin(actor);
    Row r = repo.report(id, true);
    Problem.version(r.num("version"), Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
    String reason = Input.text(b, "reason", 1000, true);
    Problem.require(
        r.text("status").equals(accept ? "SUBMITTED" : "ACCEPTED"),
        409,
        "REPORT_STATE_CONFLICT",
        "举报状态已变更");
    repo.phase(id, accept ? "ACCEPTED" : "PROCESSING", accept ? clock.instant() : null);
    repo.audit(actor, accept ? "REPORT_ACCEPTED" : "REPORT_PROCESSING", id, reason, Row.of());
    notices.record(
        r.id("reporterId"),
        null,
        "REPORT_UPDATED",
        id + ":phase:" + (r.num("version") + 1),
        Row.of("message", reason));
    return repo.report(id, false);
  }

  @Transactional
  public Row decide(UUID actor, UUID id, Map<String, Object> b) {
    identity.requireAdmin(actor);
    UUID disposition = Input.uuid(b.get("dispositionId"));
    return idem.run(
        actor,
        "disposition:" + id,
        disposition,
        b,
        200,
        () -> decideOnce(actor, id, b, disposition));
  }

  private Row decideOnce(UUID actor, UUID id, Map<String, Object> b, UUID disposition) {
    identity.requireAdmin(actor);
    Row pre = repo.report(id, false);
    String action =
        Input.choice(
            b,
            "action",
            "DISMISS",
            "HIDE_ACTIVITY",
            "CANCEL_ACTIVITY",
            "DISSOLVE_GROUP",
            "DISABLE_USER",
            "REMOVE_MEMBER",
            "CONFIRM_NO_SHOW",
            "HIDE_MESSAGE");
    String reason = Input.text(b, "reason", 1000, true);
    UUID activity =
        pre.text("targetType").equals("ACTIVITY")
            ? pre.id("targetId")
            : b.get("activityId") == null ? null : Input.uuid(b.get("activityId"));
    UUID user =
        pre.text("targetType").equals("USER")
            ? pre.id("targetId")
            : b.get("userId") == null ? subject(pre) : Input.uuid(b.get("userId"));
    List<UUID> locks = new ArrayList<>(List.of(actor, subject(pre), pre.id("reporterId")));
    if (user != null) locks.add(user);
    if (activity != null) activities.lockParticipants(activity, locks);
    else identity.lockUsers(locks);
    identity.requireAdmin(actor);
    Row before = repo.report(id, false);
    identity.lockUsers(List.of(actor, before.id("reporterId"), subject(before)));
    identity.requireAdmin(actor);
    Row r = repo.report(id, true);
    Problem.version(r.num("version"), Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
    Problem.require(
        r.text("status").equals("PROCESSING"), 409, "REPORT_STATE_CONFLICT", "请先受理并进入处理阶段");
    if (!action.equals("DISMISS"))
      Problem.require(
          Boolean.TRUE.equals(b.get("evidenceReviewed")), 400, "EVIDENCE_REQUIRED", "需核验具体证据后才能处置");
    Row d =
        Row.of(
            "action",
            action,
            "reason",
            reason,
            "activityId",
            activity,
            "userId",
            user,
            "revoked",
            false,
            "evidenceReviewed",
            b.get("evidenceReviewed"));
    switch (action) {
      case "HIDE_ACTIVITY", "CANCEL_ACTIVITY", "DISSOLVE_GROUP" -> {
        Problem.require(
            r.text("targetType").equals("ACTIVITY"), 400, "TARGET_MISMATCH", "此操作只适用于活动举报");
        if (action.equals("HIDE_ACTIVITY")) activities.hide(actor, activity, true, reason);
        else if (action.equals("CANCEL_ACTIVITY"))
          activities.cancelByGovernance(actor, activity, reason);
        else team.block(activity, true);
      }
      case "DISABLE_USER" -> {
        Problem.require(
            r.text("targetType").equals("USER") && !actor.equals(user),
            400,
            "TARGET_MISMATCH",
            "限制账号需要选择对应用户举报");
        identity.disable(user, true);
      }
      case "REMOVE_MEMBER" -> {
        Problem.require(
            activity != null && r.text("targetType").equals("USER"),
            400,
            "TARGET_MISMATCH",
            "成员移除需要用户举报和关联活动");
        team.remove(actor, activity, user, reason, true);
      }
      case "CONFIRM_NO_SHOW" -> {
        Problem.require(
            activity != null && r.text("targetType").equals("USER"),
            400,
            "TARGET_MISMATCH",
            "失约认定需要用户举报和关联活动");
        activities.find(activity);
        Problem.require(
            activities.find(activity).get("endedAt") != null, 409, "NOT_ENDED", "活动结束后才能核验失约");
        Problem.require(
            team.inSnapshot(activity, user), 400, "INVALID_FINDING_TARGET", "只可对活动结束快照中的成员核验失约");
        d.put("finding", "NO_SHOW");
      }
      case "HIDE_MESSAGE" -> {
        Problem.require(
            r.text("targetType").equals("MESSAGE"), 400, "TARGET_MISMATCH", "此操作只适用于消息举报");
        chat.hide(r.id("targetId"), true);
      }
      default -> {}
    }
    repo.decision(id, disposition, d);
    repo.audit(
        actor,
        action,
        id,
        reason,
        Row.of("dispositionId", disposition, "activityId", activity, "userId", user));
    for (UUID u : new HashSet<>(List.of(r.id("reporterId"), subject(r))))
      notices.record(
          u,
          activity,
          "REPORT_DECIDED",
          id + ":decision:" + u,
          Row.of("message", reason, "reportId", id));
    return repo.report(id, false);
  }

  @Transactional
  public Row appeal(UUID actor, UUID id, Map<String, Object> b) {
    Row before = repo.report(id, false);
    identity.lockUsers(List.of(actor, before.id("reporterId"), subject(before)));
    Row r = repo.report(id, true);
    related(actor, r);
    Problem.require(
        List.of("RESOLVED", "APPEAL_RESOLVED").contains(r.text("status")),
        409,
        "REPORT_STATE_CONFLICT",
        "当前举报尚未形成可申诉决定");
    String reason = Input.text(b, "reason", 1000, true);
    UUID aid = repo.appeal(id, actor, reason, Input.text(b, "evidence", 2000, false));
    repo.audit(actor, "APPEAL_SUBMITTED", id, reason, Row.of("appealId", aid));
    return Row.of("appealId", aid, "status", "SUBMITTED");
  }

  @Transactional
  public Row review(UUID actor, UUID appealId, Map<String, Object> b) {
    identity.requireAdmin(actor);
    Row pre = repo.appeal(appealId, false), before = repo.report(pre.id("reportId"), false);
    Row dec = new Row((Map<String, Object>) before.get("decision"));
    List<UUID> locks = new ArrayList<>(List.of(actor, subject(before), before.id("reporterId")));
    UUID activity = dec.id("activityId"), user = dec.id("userId");
    if (user != null) locks.add(user);
    if (activity != null) activities.lockParticipants(activity, locks);
    else identity.lockUsers(locks);
    identity.requireAdmin(actor);
    Row r = repo.report(pre.id("reportId"), true), a = repo.appeal(appealId, true);
    Problem.version(a.num("version"), Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
    Problem.require(
        a.text("status").equals("SUBMITTED") && r.text("status").equals("APPEAL_PENDING"),
        409,
        "REPORT_STATE_CONFLICT",
        "申诉已复核");
    String result = Input.choice(b, "result", "UPHELD", "REVOKED"),
        reason = Input.text(b, "reason", 1000, true);
    if (result.equals("REVOKED")) {
      dec.put("revoked", true);
      if (repo.otherSanctions(r.id("id"), dec.text("action"), r.id("targetId")) == 0)
        switch (dec.text("action")) {
          case "DISABLE_USER" -> identity.disable(user, false);
          case "HIDE_ACTIVITY" -> activities.hide(actor, activity, false, reason);
          case "DISSOLVE_GROUP" -> team.block(activity, false);
          case "REMOVE_MEMBER" -> team.restoreRemoved(activity, user);
          case "HIDE_MESSAGE" -> chat.hide(r.id("targetId"), false);
          default -> {}
        }
    }
    repo.resolveAppeal(appealId, result, reason);
    repo.resolveReport(r.id("id"), dec);
    repo.audit(actor, "APPEAL_" + result, r.id("id"), reason, Row.of("appealId", appealId));
    notices.record(
        a.id("appellantId"),
        activity,
        "APPEAL_RESOLVED",
        appealId + ":resolved",
        Row.of("message", reason));
    return repo.appeal(appealId, false);
  }

  public Row statistics(UUID actor, Map<String, Object> b) {
    identity.requireAdmin(actor);
    Instant
        from =
            b.containsKey("from") ? Input.time(b, "from") : Instant.parse("2000-01-01T00:00:00Z"),
        to = b.containsKey("to") ? Input.time(b, "to") : clock.instant().plusSeconds(1);
    Problem.require(from.isBefore(to), 400, "VALIDATION_FAILED", "开始时间必须早于结束时间");
    String type = String.valueOf(b.getOrDefault("type", ""));
    Problem.require(
        List.of("", "STUDY", "SPORT", "TRAVEL", "MEAL", "OTHER").contains(type),
        400,
        "VALIDATION_FAILED",
        "活动类型不合法");
    return repo.statistics(from, to, type, clock.instant());
  }

  public List<Row> audit(UUID actor) {
    identity.requireAdmin(actor);
    return repo.audit();
  }
}
