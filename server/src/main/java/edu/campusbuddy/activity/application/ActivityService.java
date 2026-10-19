package edu.campusbuddy.activity.application;

import edu.campusbuddy.activity.api.*;
import edu.campusbuddy.activity.domain.*;
import edu.campusbuddy.activity.infrastructure.*;
import edu.campusbuddy.identity.api.*;
import edu.campusbuddy.notification.api.*;
import edu.campusbuddy.shared.*;
import edu.campusbuddy.team.api.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ActivityService implements ActivityApi {
  private final ActivityRepository repo;
  private final SearchHistoryRepository searchHistory;
  private final IdentityApi identity;
  private final TeamApi team;
  private final NotificationApi notices;
  private final Clock clock;
  private final Crypto crypto;
  private final Idempotency idem;
  private final Cursor cursor;
  private final Map<String, ActivitySort> sorts = new HashMap<>();
  private final int remind, pending;

  public ActivityService(
      ActivityRepository r,
      SearchHistoryRepository sh,
      IdentityApi i,
      @Lazy TeamApi t,
      NotificationApi n,
      Clock c,
      Crypto x,
      Idempotency d,
      Cursor cur,
      List<ActivitySort> options,
      @Value("${campus.reminder-minutes:30}") int rm,
      @Value("${campus.pending-reminder-minutes:10}") int pm) {
    repo = r;
    searchHistory = sh;
    identity = i;
    team = t;
    notices = n;
    clock = c;
    crypto = x;
    idem = d;
    cursor = cur;
    options.forEach(s -> sorts.put(s.name(), s));
    remind = rm;
    pending = pm;
  }

  public Row find(UUID id) {
    return repo.find(id);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Row lock(UUID id) {
    return repo.lock(id);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Row lockOpen(UUID id) {
    Row a = repo.lock(id);
    ActivityRules.open(a, clock.instant());
    return a;
  }

  public Row search(UUID actor, Map<String, Object> b, boolean location) {
    String sort = String.valueOf(b.getOrDefault("sort", "START_TIME"));
    Problem.require(sorts.containsKey(sort), 400, "VALIDATION_FAILED", "排序方式不合法");
    // 保存搜索历史
    if (actor != null) {
      String q = b.get("q") == null ? "" : String.valueOf(b.get("q")).trim();
      if (!q.isEmpty()) {
        try {
          searchHistory.save(actor, q);
        } catch (Exception ignored) {
          // 搜索历史保存失败不影响搜索结果
        }
      }
    }
    Row page = repo.discovery(b, sort, sorts.get(sort).column(), location);
    annotateFavorites(actor, page);
    return page;
  }

  private void annotateFavorites(UUID actor, Row page) {
    Set<UUID> favorited = actor == null ? Set.of() : repo.favoriteIds(actor);
    if (page.get("items") instanceof List<?> items) {
      for (Object o : items) {
        if (o instanceof Row r) r.put("favorited", favorited.contains(r.id("id")));
      }
    }
  }

  public List<Row> searchHistory(UUID actor) {
    identity.check(actor);
    return searchHistory.list(actor, 20);
  }

  @Transactional
  public void clearSearchHistory(UUID actor) {
    identity.check(actor);
    searchHistory.clear(actor);
  }

  @Transactional
  public void deleteSearchHistory(UUID actor, String keyword) {
    identity.check(actor);
    searchHistory.delete(actor, keyword);
  }

  public Row details(UUID actor, UUID id) {
    Row a = repo.find(id);
    if (a.text("visibility").equals("HIDDEN")) throw Problem.missing();
    Row out =
        a.only(
            "id",
            "ownerId",
            "type",
            "title",
            "description",
            "campus",
            "tags",
            "startAt",
            "endAt",
            "deadline",
            "confirmationDeadline",
            "endedAt",
            "capacity",
            "status",
            "terminalState",
            "version",
            "publicLocation",
            "publicLat",
            "publicLon");
    out.put("occupied", team.occupied(id));
    out.put("remaining", a.num("capacity") - team.occupied(id));
    Row g = team.group(id);
    if (actor != null) {
      try {
        team.members(actor, id);
        if (g != null) out.put("groupId", g.get("id"));
        Row owner = identity.profile(a.id("ownerId"));
        if (owner.text("locationVisibility").equals("GROUP") || actor.equals(a.id("ownerId")))
          out.put("exactLocation", crypto.decrypt(a.text("exactLocationCiphertext")));
      } catch (Problem denied) {
        if (denied.status != 404 && denied.status != 403) throw denied;
      }
    }
    out.put("favorited", actor != null && repo.isFavorited(actor, id));
    return out;
  }

  private Row fields(Map<String, Object> b) {
    Row a = new Row();
    for (String k : List.of("title", "campus", "publicLocation"))
      a.put(k, Input.text(b, k, k.equals("title") ? 80 : k.equals("campus") ? 40 : 120, true));
    a.put("type", Input.choice(b, "type", "STUDY", "SPORT", "TRAVEL", "MEAL", "OTHER"));
    a.put("description", Input.text(b, "description", 2000, false));
    a.put("tags", Input.tags(b.get("tags")));
    for (String k : List.of("startAt", "endAt", "deadline")) a.put(k, Input.time(b, k));
    a.put(
        "confirmationDeadline",
        b.containsKey("confirmationDeadline")
            ? Input.time(b, "confirmationDeadline")
            : a.get("startAt"));
    a.put("capacity", Input.number(b, "capacity", 1, Integer.MAX_VALUE));
    a.put(
        "exactLocationCiphertext",
        b.containsKey("exactLocation")
            ? crypto.encrypt(Input.text(b, "exactLocation", 300, false))
            : b.get("exactLocationCiphertext"));
    Problem.require(
        (b.get("publicLat") == null) == (b.get("publicLon") == null),
        400,
        "VALIDATION_FAILED",
        "公开坐标需要成对填写");
    for (String k : List.of("publicLat", "publicLon"))
      a.put(
          k,
          b.get(k) == null
              ? null
              : ActivityRepository.coordinate(b.get(k), k.equals("publicLat") ? 90 : 180));
    return a;
  }

  @Transactional
  public Row publish(UUID actor, Map<String, Object> b, UUID key) {
    identity.requireVerified(actor);
    return idem.run(
        actor,
        "publish",
        key,
        b,
        201,
        () -> {
          identity.lockUsers(List.of(actor));
          identity.requireVerified(actor);
          // The encrypted column is server-owned: never accept a client-supplied ciphertext.
          Row input = new Row(b);
          input.remove("exactLocationCiphertext");
          Row a = fields(input);
          ActivityRules.validate(a, clock.instant());
          team.requireNoConflict(actor, a.time("startAt"), a.time("endAt"), null);
          UUID id = UUID.randomUUID();
          a.put("id", id);
          a.put("ownerId", actor);
          repo.create(a);
          team.addOwner(id, actor);
          notices.record(
              actor,
              id,
              "ACTIVITY_PUBLISHED",
              id + ":published",
              Row.of("title", a.text("title"), "message", "活动已发布"));
          return Row.of("activityId", id, "id", id, "version", 0, "status", "OPEN");
        });
  }

  private Row lockMembers(UUID id, UUID actor) {
    return lockParticipants(id, actor == null ? List.of() : List.of(actor));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Row lockParticipants(UUID id, Collection<UUID> extraUsers) {
    List<UUID> before = team.activeUsers(id);
    List<UUID> locks = new ArrayList<>(before);
    locks.addAll(extraUsers);
    identity.lockUsers(locks);
    Row a = repo.lock(id);
    Problem.require(
        new HashSet<>(before).equals(new HashSet<>(team.activeUsers(id))),
        503,
        "TEMPORARILY_UNAVAILABLE",
        "成员刚刚更新，请保留请求重试");
    return a;
  }

  private void owner(Row a, UUID actor) {
    identity.check(actor);
    Problem.require(actor.equals(a.id("ownerId")), 403, "FORBIDDEN", "仅发起人可操作");
  }

  @Transactional
  public Row change(UUID actor, UUID id, Map<String, Object> b) {
    Problem.require(
        b.keySet().stream()
            .anyMatch(
                k ->
                    List.of(
                            "title",
                            "type",
                            "description",
                            "campus",
                            "tags",
                            "startAt",
                            "endAt",
                            "deadline",
                            "confirmationDeadline",
                            "capacity",
                            "publicLocation",
                            "exactLocation",
                            "publicLat",
                            "publicLon")
                        .contains(k)),
        400,
        "VALIDATION_FAILED",
        "请填写变更内容");
    Row old = lockMembers(id, actor);
    owner(old, actor);
    Problem.version(old.num("version"), Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
    ActivityRules.editable(old, clock.instant());
    Row merged = new Row(old);
    b.forEach((k, v) -> {
      if (!k.equals("exactLocationCiphertext")) merged.put(k, v);
    });
    Row a = fields(merged);
    a.put("id", id);
    a.put("ownerId", actor);
    ActivityRules.validate(a, clock.instant());
    Problem.require(a.num("capacity") >= team.occupied(id), 409, "CAPACITY_FULL", "容量不能少于当前成员人数");
    for (UUID u : team.activeUsers(id))
      team.requireNoConflict(u, a.time("startAt"), a.time("endAt"), id);
    boolean major =
        List.of("startAt", "endAt", "deadline", "confirmationDeadline", "publicLocation").stream()
                .anyMatch(k -> !Objects.equals(old.get(k), a.get(k)))
            || b.containsKey("exactLocation");
    repo.change(a);
    Row next = repo.find(id);
    repo.log(old, next, actor, major ? "时间或地点等重要信息已变更" : "活动信息已更新");
    team.changed(next, major && clock.instant().isBefore(next.time("confirmationDeadline")));
    return details(actor, id);
  }

  private Row cancelLocked(UUID actor, Row a, String reason) {
    Problem.require(
        List.of("OPEN", "IN_PROGRESS").contains(a.text("status")),
        409,
        "ACTIVITY_TERMINAL",
        "活动已经结束或取消");
    repo.status(a.id("id"), "CANCELLED", null);
    team.closePending(a, "CANCELLED");
    team.readOnly(a.id("id"));
    Row next = repo.find(a.id("id"));
    repo.log(a, next, actor, reason);
    for (UUID u : team.affected(a.id("id")))
      notices.record(
          u,
          a.id("id"),
          "ACTIVITY_CANCELLED",
          a.id("id") + ":cancel:" + next.num("version") + ":" + u,
          Row.of("message", reason));
    return details(actor, a.id("id"));
  }

  @Transactional
  public Row cancel(UUID actor, UUID id, Map<String, Object> b) {
    Row a = lockMembers(id, actor);
    owner(a, actor);
    Problem.version(a.num("version"), Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
    return cancelLocked(actor, a, Input.text(b, "reason", 500, true));
  }

  @Transactional
  public void cancelByGovernance(UUID actor, UUID id, String reason) {
    Row a = lockMembers(id, actor);
    identity.requireAdmin(actor);
    cancelLocked(actor, a, reason);
  }

  private Row end(Row a, Instant at) {
    if (a.text("status").equals("ENDED") || a.text("terminalState").equals("ENDED")) return a;
    Problem.require(
        List.of("OPEN", "IN_PROGRESS").contains(a.text("status")),
        409,
        "ACTIVITY_TERMINAL",
        "取消活动不能正常结束");
    repo.status(a.id("id"), "ENDED", at);
    Row next = repo.find(a.id("id"));
    team.closePending(next, "EXPIRED");
    team.ended(next, at);
    return next;
  }

  @Transactional
  public Row complete(UUID actor, UUID id, Map<String, Object> b) {
    Row a = lockMembers(id, actor);
    owner(a, actor);
    if (a.text("status").equals("ENDED")) return a.only("id", "status", "version", "endedAt");
    Problem.version(a.num("version"), Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
    Instant now = clock.instant();
    Problem.require(!now.isBefore(a.time("startAt")), 409, "NOT_STARTED", "活动开始后才可结束");
    return end(a, now.isBefore(a.time("endAt")) ? now : a.time("endAt"))
        .only("id", "status", "version", "endedAt");
  }

  @Transactional
  public Row archive(UUID actor, UUID id, Map<String, Object> b) {
    Row a = lockMembers(id, actor);
    owner(a, actor);
    Problem.version(a.num("version"), Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
    Problem.require(
        List.of("ENDED", "CANCELLED").contains(a.text("status")),
        409,
        "ACTIVITY_NOT_TERMINAL",
        "只可归档已结束或取消的活动");
    repo.status(id, "ARCHIVED", null);
    return repo.find(id).only("id", "status", "version", "endedAt", "terminalState");
  }

  @Transactional
  public void advance(UUID id) {
    Row a = lockMembers(id, null);
    Instant now = clock.instant();
    if (!List.of("OPEN", "IN_PROGRESS").contains(a.text("status"))) return;
    if (!now.isBefore(a.time("endAt"))) {
      end(a, a.time("endAt"));
      return;
    }
    if (!now.isBefore(a.time("deadline"))) {
      team.closePending(a, "EXPIRED");
      for (UUID u : team.activeUsers(id))
        notices.record(
            u,
            id,
            "DEADLINE_REACHED",
            id + ":deadline:" + a.time("deadline") + ":" + u,
            Row.of("message", "报名已截止"));
    } else if (!now.isBefore(a.time("deadline").minusSeconds(pending * 60L))
        && a.time("createdAt").isBefore(a.time("deadline").minusSeconds(pending * 60L))) {
      for (UUID u : team.affected(id))
        if (!team.activeUsers(id).contains(u)) {
          notices.record(
              a.id("ownerId"),
              id,
              "PENDING_REMINDER",
              id + ":pending:" + a.num("version"),
              Row.of(
                  "message",
                  "报名即将截止，请审核待处理申请",
                  "reminder",
                  true,
                  "activityVersion",
                  a.num("version")));
          break;
        }
    }
    if (now.isBefore(a.time("startAt"))
        && !now.isBefore(a.time("startAt").minusSeconds(remind * 60L))
        && a.time("createdAt").isBefore(a.time("startAt").minusSeconds(remind * 60L)))
      for (UUID u : team.activeUsers(id))
        notices.record(
            u,
            id,
            "START_REMINDER",
            id + ":start:" + a.num("version") + ":" + u,
            Row.of(
                "message",
                "活动即将开始，请查看最新安排",
                "reminder",
                true,
                "activityVersion",
                a.num("version")));
    if (!now.isBefore(a.time("startAt")) && a.text("status").equals("OPEN"))
      repo.status(id, "IN_PROGRESS", null);
  }

  public List<UUID> due() {
    return repo.due(clock.instant(), remind, pending);
  }

  @Transactional
  public void hide(UUID actor, UUID id, boolean hidden, String reason) {
    Row a = lockMembers(id, actor);
    identity.requireAdmin(actor);
    repo.hide(id, hidden);
    Row next = repo.find(id);
    repo.log(a, next, actor, reason);
    for (UUID u : team.affected(id))
      notices.record(
          u,
          id,
          "GOVERNANCE",
          id + ":hidden:" + next.num("version") + ":" + u,
          Row.of("message", reason));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void scrubOwner(UUID id) {
    repo.scrub(id);
  }

  // ===== 活动收藏 =====
  @Transactional
  public Row favorite(UUID actor, UUID activityId) {
    identity.check(actor);
    Row a = repo.find(activityId);
    if (a.text("visibility").equals("HIDDEN")) throw Problem.missing();
    repo.addFavorite(actor, activityId);
    return Row.of("activityId", activityId, "favorited", true, "count", repo.favoriteCount(activityId));
  }

  @Transactional
  public Row unfavorite(UUID actor, UUID activityId) {
    identity.check(actor);
    repo.removeFavorite(actor, activityId);
    return Row.of("activityId", activityId, "favorited", false, "count", repo.favoriteCount(activityId));
  }

  public Row favorites(UUID actor, String token, int size) {
    identity.check(actor);
    String context = "favorites:" + actor;
    Row pos = cursor.decode(token, context);
    List<Row> items =
        repo.favorites(
            actor,
            pos == null ? null : pos.time("at"),
            pos == null ? null : pos.id("id"),
            size + 1);
    return cursor.page(items, size, context, "favoritedAt");
  }

  public boolean isFavorited(UUID actor, UUID activityId) {
    if (actor == null) return false;
    return repo.isFavorited(actor, activityId);
  }
}
