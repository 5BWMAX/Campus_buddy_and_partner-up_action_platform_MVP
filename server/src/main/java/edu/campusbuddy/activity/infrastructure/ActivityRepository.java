package edu.campusbuddy.activity.infrastructure;

import edu.campusbuddy.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityRepository {
  private final Sql sql;
  private final Json json;
  private final Cursor cursor;

  public ActivityRepository(Sql s, Json j, Cursor c) {
    sql = s;
    json = j;
    cursor = c;
  }

  public Row find(UUID id) {
    return sql.must("select * from activity where id=?", id);
  }

  public Row lock(UUID id) {
    return sql.must("select * from activity where id=? for update", id);
  }

  public void create(Row a) {
    sql.update(
        "insert into"
            + " activity(id,owner_id,type,title,description,campus,tags,start_at,end_at,deadline,confirmation_deadline,capacity,public_location,exact_location_ciphertext,public_lat,public_lon)"
            + " values(?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?)",
        a.id("id"),
        a.id("ownerId"),
        a.text("type"),
        a.text("title"),
        a.text("description"),
        a.text("campus"),
        json.write(a.get("tags")),
        a.time("startAt"),
        a.time("endAt"),
        a.time("deadline"),
        a.time("confirmationDeadline"),
        a.num("capacity"),
        a.text("publicLocation"),
        a.get("exactLocationCiphertext"),
        a.get("publicLat"),
        a.get("publicLon"));
  }

  public void change(Row a) {
    sql.update(
        "update activity set"
            + " type=?,title=?,description=?,campus=?,tags=?::jsonb,start_at=?,end_at=?,deadline=?,confirmation_deadline=?,capacity=?,public_location=?,exact_location_ciphertext=?,public_lat=?,public_lon=?,version=version+1,updated_at=now()"
            + " where id=?",
        a.text("type"),
        a.text("title"),
        a.text("description"),
        a.text("campus"),
        json.write(a.get("tags")),
        a.time("startAt"),
        a.time("endAt"),
        a.time("deadline"),
        a.time("confirmationDeadline"),
        a.num("capacity"),
        a.text("publicLocation"),
        a.get("exactLocationCiphertext"),
        a.get("publicLat"),
        a.get("publicLon"),
        a.id("id"));
  }

  public void log(Row old, Row next, UUID actor, String summary) {
    sql.update(
        "insert into"
            + " activity_change(id,activity_id,old_version,new_version,old_values,new_values,summary,actor_id,request_id)"
            + " values(?,?,?,?,?::jsonb,?::jsonb,?,?,?)",
        UUID.randomUUID(),
        old.id("id"),
        old.num("version"),
        next.num("version"),
        json.write(old),
        json.write(next),
        summary,
        actor,
        Trace.id());
  }

  public void status(UUID id, String status, Instant ended) {
    sql.update(
        "update activity set status=?,terminal_state=case when ? in ('ENDED','CANCELLED') then ?"
            + " else terminal_state"
            + " end,ended_at=coalesce(?,ended_at),version=version+1,updated_at=now() where id=?",
        status,
        status,
        status,
        ended,
        id);
  }

  public void hide(UUID id, boolean hidden) {
    sql.update(
        "update activity set visibility=?,version=version+1 where id=?",
        hidden ? "HIDDEN" : "VISIBLE",
        id);
  }

  public void scrub(UUID owner) {
    sql.list("select id from activity where owner_id=? order by id for update", owner);
    sql.update("update activity set exact_location_ciphertext=null where owner_id=?", owner);
    sql.update(
        "update activity_change set"
            + " old_values=old_values-'exactLocationCiphertext',new_values=new_values-'exactLocationCiphertext'"
            + " where activity_id in (select id from activity where owner_id=?)",
        owner);
  }

  public List<UUID> due(Instant now, int reminderMinutes, int pendingMinutes) {
    return sql
        .list(
            "select id from activity where status in ('OPEN','IN_PROGRESS') and (end_at<=? or"
                + " start_at-make_interval(mins=>?)<=? or deadline-make_interval(mins=>?)<=?) order"
                + " by end_at",
            now,
            reminderMinutes,
            now,
            pendingMinutes,
            now)
        .stream()
        .map(a -> a.id("id"))
        .toList();
  }

  public Row discovery(
      Map<String, Object> filter, String sort, String sortColumn, boolean locationAllowed) {
    Row f = new Row(filter);
    String token = f.text("cursor");
    f.remove("cursor");
    int size = Cursor.size(f.remove("pageSize"));
    f.put("sort", sort);
    String context = json.hash(f);
    Row pos = cursor.decode(token, context);
    List<Object> args = new ArrayList<>();
    String distance = "null::double precision";
    if (sort.equals("DISTANCE")) {
      Problem.require(
          locationAllowed && Boolean.TRUE.equals(f.get("locationConsent")),
          400,
          "LOCATION_PERMISSION_REQUIRED",
          "请先授权使用当前位置");
      double lat = coordinate(f.get("lat"), 90), lon = coordinate(f.get("lon"), 180);
      distance =
          "coalesce(6371000*2*asin(sqrt(least(1.0,power(sin(radians(a.public_lat-?)/2),2)+cos(radians(?))*cos(radians(a.public_lat))*power(sin(radians(a.public_lon-?)/2),2)))),1e20)";
      args.add(lat);
      args.add(lat);
      args.add(lon);
    }
    String q =
        "select * from (select"
            + " a.id,a.owner_id,a.title,a.type,a.description,a.campus,a.tags,a.start_at,a.end_at,a.deadline,a.confirmation_deadline,a.capacity,a.status,a.terminal_state,a.version,a.ended_at,a.public_location,a.public_lat,a.public_lon,u.nickname"
            + " as owner_name,coalesce(p.n,0) as occupied,a.capacity-coalesce(p.n,0) as remaining,"
            + distance
            + " as distance from activity a join user_account u on u.id=a.owner_id left join"
            + " (select activity_id,count(*) n from participation where status='ACTIVE' group by"
            + " activity_id) p on p.activity_id=a.id where a.visibility='VISIBLE'";
    for (String k : List.of("type", "campus")) {
      if (!f.text(k).isBlank()) {
        q += " and a." + k + "=?";
        args.add(f.text(k));
      }
    }
    if (!f.text("q").isBlank()) {
      q += " and (a.title ilike ? or a.description ilike ? or a.public_location ilike ?)";
      String text =
          "%" + f.text("q").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      args.add(text);
      args.add(text);
      args.add(text);
    }
    if (!f.text("tag").isBlank()) {
      q += " and a.tags @> ?::jsonb";
      args.add(json.write(List.of(f.text("tag"))));
    }
    for (String k : List.of("from", "to")) {
      if (!f.text(k).isBlank()) {
        q += " and a.start_at " + (k.equals("from") ? ">=" : "<") + "?";
        args.add(Input.time(f, k));
      }
    }
    if (f.containsKey("minRemaining") && !f.text("minRemaining").isBlank()) {
      int n;
      try {
        n = Integer.parseInt(f.text("minRemaining"));
      } catch (Exception e) {
        throw new Problem(400, "VALIDATION_FAILED", "剩余人数不合法");
      }
      Problem.require(n >= 0, 400, "VALIDATION_FAILED", "剩余人数不能为负数");
      q += " and a.capacity-coalesce(p.n,0)>=?";
      args.add(n);
    }
    q += ") results where true";
    if (pos != null) {
      q += " and (" + sortColumn + ",id)>(?,?)";
      args.add(sort.equals("DISTANCE") ? ((Number) pos.get("at")).doubleValue() : pos.time("at"));
      args.add(pos.id("id"));
    }
    q += " order by " + sortColumn + ",id limit ?";
    args.add(size + 1);
    List<Row> items = sql.list(q, args.toArray());
    return cursor.page(items, size, context, sort.equals("DISTANCE") ? "distance" : "startAt");
  }

  public static double coordinate(Object o, double max) {
    try {
      double n = Double.parseDouble(String.valueOf(o));
      Problem.require(
          Double.isFinite(n) && Math.abs(n) <= max, 400, "VALIDATION_FAILED", "坐标范围不合法");
      return n;
    } catch (NumberFormatException e) {
      throw new Problem(400, "VALIDATION_FAILED", "坐标范围不合法");
    }
  }
}
