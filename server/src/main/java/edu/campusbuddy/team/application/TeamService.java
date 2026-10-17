package edu.campusbuddy.team.application;

import edu.campusbuddy.activity.api.*;
import edu.campusbuddy.identity.api.*;
import edu.campusbuddy.notification.api.*;
import edu.campusbuddy.shared.*;
import edu.campusbuddy.team.api.*;
import edu.campusbuddy.team.infrastructure.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TeamService implements TeamApi {
  private final TeamRepository repo;
  private final ActivityApi activities;
  private final IdentityApi identity;
  private final NotificationApi notices;
  private final Idempotency idem;
  private final Clock clock;

  public TeamService(
      TeamRepository r,
      @Lazy ActivityApi a,
      IdentityApi i,
      NotificationApi n,
      Idempotency d,
      Clock c) {
    repo = r;
    activities = a;
    identity = i;
    notices = n;
    idem = d;
    clock = c;
  }

  public boolean inSnapshot(UUID activity, UUID user) {
    return repo.snapshots(activity).stream().anyMatch(s -> user.equals(s.id("userId")));
  }

  public List<UUID> activeUsers(UUID activity) {
    return repo.active(activity);
  }

  public long occupied(UUID activity) {
    return repo.occupied(activity);
  }

  public Row group(UUID activity) {
    return repo.group(activity);
  }

  public List<UUID> affected(UUID activity) {
    return repo.affected(activity);
  }

  public void requireNoConflict(UUID user, Instant from, Instant to, UUID exclude) {
    Problem.require(!repo.conflict(user, from, to, exclude), 409, "TIME_CONFLICT", "与已加入的活动时间重叠");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void addOwner(UUID activity, UUID user) {
    repo.activate(activity, user, "OWNER");
  }

  private void room(Row a) {
    Problem.require(
        occupied(a.id("id")) < a.num("capacity"), 409, "CAPACITY_FULL", "活动名额已满，申请未获批准");
  }

  private void owner(UUID actor, Row a) {
    Problem.require(actor.equals(a.id("ownerId")), 403, "FORBIDDEN", "只有发起人可审核");
  }

  private void version(Row app, Map<String, Object> b) {
    Problem.require(
        app.num("round") == Input.number(b, "round", 1, Integer.MAX_VALUE),
        409,
        "ROUND_MISMATCH",
        "申请轮次已更新");
    Problem.version(app.num("version"), Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
    Problem.require(
        app.text("status").equals("PENDING"), 409, "APPLICATION_NOT_PENDING", "当前申请不再待审核");
  }

  @Transactional
  public Row apply(UUID actor, UUID activity, Map<String, Object> b, UUID key) {
    identity.requireVerified(actor);
    return idem.run(
        actor,
        "apply:" + activity,
        key,
        b,
        201,
        () -> {
          UUID owner = activities.find(activity).id("ownerId");
          identity.lockUsersAndReferences(List.of(actor), List.of(owner));
          identity.requireVerified(actor);
          Row a = activities.lockOpen(activity);
          Problem.require(!actor.equals(a.id("ownerId")), 409, "OWN_ACTIVITY", "不能申请自己的活动");
          room(a);
          requireNoConflict(actor, a.time("startAt"), a.time("endAt"), activity);
          Row prior = repo.application(activity, actor), part = repo.participation(activity, actor);
          Problem.require(
              part == null || !part.text("status").equals("REMOVED"),
              403,
              "MEMBER_REMOVED",
              "被移除后需先完成治理复核");
          Problem.require(
              (prior == null || !prior.text("status").equals("PENDING"))
                  && (part == null || !part.text("status").equals("ACTIVE")),
              409,
              "DUPLICATE_APPLICATION",
              "已有待审核申请或已经入组");
          Row app = repo.submit(activity, actor, Input.text(b, "note", 300, false));
          repo.history(app, prior == null ? null : prior.text("status"), "PENDING", actor, "");
          for (UUID u : List.of(actor, a.id("ownerId")))
            notices.record(
                u,
                activity,
                "APPLICATION_SUBMITTED",
                app.id("id") + ":" + app.num("round") + ":submitted:" + u,
                Row.of(
                    "applicationId",
                    app.id("id"),
                    "message",
                    u.equals(actor) ? "申请已提交，等待审核" : "有新的待审核申请"));
          return app.only("id", "status", "round", "version");
        });
  }

  @Transactional
  public Row approve(UUID actor, UUID activity, UUID appId, Map<String, Object> b, UUID key) {
    identity.requireVerified(actor);
    Row pre = repo.application(appId, false);
    Problem.require(pre.id("activityId").equals(activity), 404, "RESOURCE_NOT_FOUND", "申请不存在");
    owner(actor, activities.find(activity));
    return idem.run(
        actor,
        "approve:" + activity + ":" + appId,
        key,
        b,
        200,
        () -> {
          identity.lockUsers(List.of(actor, pre.id("userId")));
          identity.requireVerified(actor);
          identity.requireVerified(pre.id("userId"));
          Row a = activities.lockOpen(activity);
          owner(actor, a);
          Row app = repo.application(appId, true);
          Problem.require(
              app.id("activityId").equals(activity) && app.id("userId").equals(pre.id("userId")),
              404,
              "RESOURCE_NOT_FOUND",
              "申请不存在");
          version(app, b);
          room(a);
          requireNoConflict(app.id("userId"), a.time("startAt"), a.time("endAt"), activity);
          repo.status(app, "APPROVED", actor, "");
          Row p = repo.activate(activity, app.id("userId"), "MEMBER"),
              g = repo.ensureGroup(activity);
          for (UUID u : List.of(actor, app.id("userId")))
            notices.record(
                u,
                activity,
                "APPLICATION_APPROVED",
                appId + ":" + app.num("round") + ":approved:" + u,
                Row.of("applicationId", appId, "groupId", g.id("id"), "message", "申请已通过，临时小组已就绪"));
          return Row.of(
              "status",
              "APPROVED",
              "groupId",
              g.id("id"),
              "participationId",
              p.id("id"),
              "occupied",
              occupied(activity),
              "capacity",
              a.num("capacity"),
              "applicationVersion",
              app.num("version") + 1,
              "activityVersion",
              a.num("version"));
        });
  }

  @Transactional
  public Row resolve(
      UUID actor, UUID activity, UUID appId, Map<String, Object> b, boolean withdraw) {
    Row pre = repo.application(appId, false);
    if (withdraw) {
      UUID owner = activities.find(activity).id("ownerId");
      identity.lockUsersAndReferences(List.of(actor, pre.id("userId")), List.of(owner));
    } else identity.lockUsers(List.of(actor, pre.id("userId")));
    identity.check(actor);
    Row a = activities.lock(activity);
    Row app = repo.application(appId, true);
    Problem.require(app.id("activityId").equals(activity), 404, "RESOURCE_NOT_FOUND", "申请不存在");
    if (withdraw) Problem.require(actor.equals(app.id("userId")), 403, "FORBIDDEN", "仅可撤回自己的申请");
    else owner(actor, a);
    version(app, b);
    Problem.require(clock.instant().isBefore(a.time("deadline")), 409, "DEADLINE_PASSED", "报名已截止");
    String reason = withdraw ? "主动撤回" : Input.text(b, "reason", 500, true),
        status = withdraw ? "WITHDRAWN" : "REJECTED";
    repo.status(app, status, actor, reason);
    for (UUID u : List.of(a.id("ownerId"), app.id("userId")))
      notices.record(
          u,
          activity,
          "APPLICATION_" + status,
          appId + ":" + app.num("round") + ":" + status + ":" + u,
          Row.of("message", reason));
    return repo.application(appId, false).only("id", "status", "round", "version");
  }

  @Transactional
  public Row exit(UUID actor, UUID activity, Map<String, Object> b, UUID key) {
    identity.check(actor);
    return idem.run(
        actor,
        "exit:" + activity,
        key,
        b,
        200,
        () -> {
          UUID owner = activities.find(activity).id("ownerId");
          identity.lockUsersAndReferences(List.of(actor), List.of(owner));
          identity.check(actor);
          Row a = activities.lock(activity);
          Problem.require(
              !actor.equals(a.id("ownerId")),
              409,
              "OWNER_EXIT_REQUIRES_CANCEL",
              "发起人需要取消活动，不能直接退出");
          Row p = repo.participation(activity, actor);
          if (p == null) throw Problem.missing();
          if (!p.text("status").equals("ACTIVE"))
            return Row.of("status", p.text("status"), "occupied", occupied(activity));
          Problem.require(
              a.text("status").equals("OPEN")
                  && clock.instant().isBefore(a.time("confirmationDeadline")),
              409,
              "CONFIRMATION_CLOSED",
              "确认截止后不能主动退出");
          String reason = Input.text(b, "reason", 500, true);
          repo.release(activity, actor, "LEFT", clock.instant());
          for (UUID u : List.of(actor, a.id("ownerId")))
            notices.record(
                u,
                activity,
                "MEMBER_EXIT",
                p.id("id") + ":exit:" + p.num("version") + ":" + u,
                Row.of("message", reason));
          return Row.of("status", "LEFT", "occupied", occupied(activity));
        });
  }

  @Transactional
  public Row confirm(UUID actor, UUID activity, Map<String, Object> b) {
    identity.lockUsers(List.of(actor));
    identity.check(actor);
    Row a = activities.lock(activity);
    Row p = repo.participation(activity, actor);
    if (p == null || !p.text("status").equals("ACTIVE")) throw Problem.missing();
    Problem.require(
        clock.instant().isBefore(a.time("confirmationDeadline")) && a.text("status").equals("OPEN"),
        409,
        "CONFIRMATION_CLOSED",
        "已超过确认截止时间");
    Problem.version(p.num("version"), Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
    repo.confirm(activity, actor, Input.choice(b, "status", "CONFIRMED", "LEAVE"));
    return repo.participation(activity, actor).only("id", "confirmation", "version", "status");
  }

  @Transactional
  public Row checkin(UUID actor, UUID activity, Map<String, Object> b) {
    identity.lockUsers(List.of(actor));
    identity.check(actor);
    Row a = activities.lock(activity);
    Row p = repo.participation(activity, actor);
    if (p == null || !p.text("status").equals("ACTIVE")) throw Problem.missing();
    Input.choice(b, "status", "SELF_REPORTED");
    if (p.get("checkinAt") != null) return p.only("checkinAt", "checkinStatus", "version");
    Instant now = clock.instant();
    Problem.require(
        !now.isBefore(a.time("startAt"))
            && now.isBefore(a.time("endAt"))
            && List.of("OPEN", "IN_PROGRESS").contains(a.text("status")),
        409,
        "CHECKIN_CLOSED",
        "只能在活动进行期间自报签到");
    repo.checkin(activity, actor, now);
    return repo.participation(activity, actor).only("checkinAt", "checkinStatus", "version");
  }

  @Transactional
  public Row grant(UUID actor, UUID group, boolean write) {
    Row g = repo.groupById(group);
    if (write) identity.lockUserReferences(List.of(actor));
    Row a =
        write ? activities.lock(g.id("activityId")) : activities.share(g.id("activityId"));
    g = repo.groupById(group);
    try {
      identity.check(actor);
    } catch (Problem e) {
      throw Problem.missing();
    }
    Row p = repo.participation(a.id("id"), actor);
    if (p == null || !p.text("status").equals("ACTIVE") || g.text("accessMode").equals("BLOCKED"))
      throw Problem.missing();
    boolean readOnly =
        !g.text("accessMode").equals("ACTIVE")
            || !List.of("OPEN", "IN_PROGRESS").contains(a.text("status"))
            || !clock.instant().isBefore(a.time("endAt"))
            || a.text("visibility").equals("HIDDEN");
    Problem.require(!write || !readOnly, 409, "GROUP_READ_ONLY", "当前小组仅可查看历史消息");
    return Row.of(
        "activityId",
        a.id("id"),
        "ownerId",
        a.id("ownerId"),
        "role",
        p.text("role"),
        "accessMode",
        readOnly ? "READ_ONLY" : "ACTIVE",
        "groupId",
        group);
  }

  @Transactional
  public UUID requireActivityDetailsAccess(UUID actor, UUID activity) {
    activities.share(activity);
    try {
      identity.check(actor);
    } catch (Problem e) {
      throw Problem.missing();
    }
    Row access = repo.activityDetailsAccess(activity, actor);
    if (access == null
        || !access.text("status").equals("ACTIVE")
        || access.text("accessMode").equals("BLOCKED")) throw Problem.missing();
    return access.id("groupId");
  }

  @Transactional
  public List<Row> members(UUID actor, UUID activity) {
    Row a = activities.share(activity);
    try {
      identity.check(actor);
    } catch (Problem e) {
      throw Problem.missing();
    }
    Row p = repo.participation(activity, actor);
    Row g = repo.group(activity);
    if (p == null
        || !p.text("status").equals("ACTIVE")
        || (g != null && g.text("accessMode").equals("BLOCKED"))) throw Problem.missing();
    List<Row> participations = repo.activeParticipations(activity);
    Map<UUID, Row> profiles =
        identity.memberProfiles(participations.stream().map(x -> x.id("userId")).toList());
    return participations.stream()
        .map(
            participation -> {
              Row profile = profiles.get(participation.id("userId"));
              if (profile == null) throw Problem.missing();
              Row out = new Row(profile);
              out.putAll(
                  participation.only(
                      "role", "confirmation", "version", "checkinStatus", "checkinAt"));
              return out;
            })
        .toList();
  }

  @Transactional
  public Row feedbackGrant(UUID actor, UUID activity, Collection<UUID> targets) {
    identity.check(actor);
    Row a = activities.lock(activity);
    List<Row> snapshot = repo.snapshots(activity);
    Row own =
        snapshot.stream()
            .filter(p -> actor.equals(p.id("userId")))
            .findFirst()
            .orElseThrow(Problem::missing);
    Instant now = clock.instant(), ended = own.time("endedAt");
    Problem.require(
        !now.isBefore(ended) && now.isBefore(ended.plusSeconds(86400)),
        409,
        "FEEDBACK_CLOSED",
        "反馈窗口为活动结束后的24小时");
    Row g = repo.group(activity);
    Problem.require(
        g == null || !g.text("accessMode").equals("BLOCKED"),
        403,
        "FEEDBACK_RESTRICTED",
        "小组已被治理限制");
    Set<UUID> valid = new HashSet<>();
    snapshot.forEach(s -> valid.add(s.id("userId")));
    for (UUID t : targets)
      Problem.require(
          !t.equals(actor) && valid.contains(t), 400, "INVALID_RATING_TARGET", "只能评价同一结束快照中的其他成员");
    return Row.of("endedAt", ended, "members", snapshot);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void closePending(Row a, String state) {
    for (Row app : repo.pending(a.id("id"))) {
      repo.status(app, state, a.id("ownerId"), state.equals("EXPIRED") ? "报名截止" : "活动取消");
      notices.record(
          app.id("userId"),
          a.id("id"),
          "APPLICATION_" + state,
          app.id("id") + ":" + app.num("round") + ":" + state,
          Row.of("message", state.equals("EXPIRED") ? "申请已过期" : "活动已取消"));
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void changed(Row a, boolean reset) {
    if (reset) repo.reset(a.id("id"));
    for (UUID u : affected(a.id("id")))
      notices.record(
          u,
          a.id("id"),
          "ACTIVITY_CHANGED",
          a.id("id") + ":change:" + a.num("version") + ":" + u,
          Row.of(
              "message",
              reset ? "活动已变更，请重新确认参加状态" : "活动安排已更新",
              "activityVersion",
              a.num("version")));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void ended(Row a, Instant at) {
    repo.snapshot(a.id("id"), at);
    for (UUID u : repo.active(a.id("id")))
      notices.record(
          u,
          a.id("id"),
          "FEEDBACK_REQUEST",
          a.id("id") + ":feedback:" + u,
          Row.of("message", "活动已结束，请在24小时内反馈"));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void readOnly(UUID activity) {
    repo.readOnly(activity);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void block(UUID activity, boolean value) {
    repo.block(activity, value);
  }

  @Transactional
  public Row remove(UUID actor, UUID activity, UUID user, String reason, boolean governance) {
    identity.lockUsers(List.of(actor, user));
    identity.check(actor);
    Row a = activities.lock(activity);
    if (governance) identity.requireAdmin(actor);
    else owner(actor, a);
    Problem.require(
        !user.equals(a.id("ownerId")), 409, "OWNER_REMOVAL_REQUIRES_CANCEL", "移除发起人需要先取消活动");
    Problem.require(reason != null && !reason.isBlank(), 400, "VALIDATION_FAILED", "移除理由必填");
    Row p = repo.participation(activity, user);
    if (p == null) throw Problem.missing();
    if (p.text("status").equals("ACTIVE")) {
      repo.release(activity, user, "REMOVED", clock.instant());
      for (UUID u : List.of(user, a.id("ownerId")))
        notices.record(
            u,
            activity,
            "MEMBER_REMOVED",
            p.id("id") + ":remove:" + p.num("version") + ":" + u,
            Row.of("message", reason));
    }
    return Row.of(
        "status",
        repo.participation(activity, user).text("status"),
        "occupied",
        occupied(activity));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void restoreRemoved(UUID activity, UUID user) {
    repo.restore(activity, user);
  }

  public List<Row> applications(UUID actor, UUID activity) {
    identity.check(actor);
    owner(actor, activities.find(activity));
    return repo.applications(activity);
  }

  public List<Row> mine(UUID user) {
    identity.check(user);
    return repo.mine(user);
  }
}
