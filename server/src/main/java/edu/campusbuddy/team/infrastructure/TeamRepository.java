package edu.campusbuddy.team.infrastructure;

import edu.campusbuddy.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class TeamRepository {
  private final Sql sql;

  public TeamRepository(Sql s) {
    sql = s;
  }

  public List<UUID> active(UUID activity) {
    return sql
        .list(
            "select user_id from participation where activity_id=? and status='ACTIVE' order by"
                + " user_id",
            activity)
        .stream()
        .map(r -> r.id("userId"))
        .toList();
  }

  public long occupied(UUID activity) {
    return sql.count(
        "select count(*) from participation where activity_id=? and status='ACTIVE'", activity);
  }

  public boolean conflict(UUID user, Instant from, Instant to, UUID exclude) {
    return sql.count(
            "select count(*) from participation p join activity a on a.id=p.activity_id where"
                + " p.user_id=? and p.status='ACTIVE' and a.status in ('OPEN','IN_PROGRESS') and"
                + " a.start_at<? and a.end_at>? and (?::uuid is null or a.id<>?)",
            user,
            to,
            from,
            exclude,
            exclude)
        > 0;
  }

  public Row application(UUID id, boolean lock) {
    return sql.must("select * from application where id=?" + (lock ? " for update" : ""), id);
  }

  public Row application(UUID activity, UUID user) {
    return sql.one("select * from application where activity_id=? and user_id=?", activity, user);
  }

  public Row submit(UUID activity, UUID user, String note) {
    Row old = application(activity, user);
    if (old == null) {
      UUID id = UUID.randomUUID();
      sql.update(
          "insert into application(id,activity_id,user_id,note) values(?,?,?,?)",
          id,
          activity,
          user,
          note);
      return application(id, false);
    }
    sql.update(
        "update application set"
            + " round=round+1,version=version+1,status='PENDING',note=?,updated_at=now() where"
            + " id=?",
        note,
        old.id("id"));
    return application(old.id("id"), false);
  }

  public void status(Row app, String state, UUID actor, String reason) {
    String from = app.text("status");
    sql.update(
        "update application set status=?,version=version+1,updated_at=now() where id=?",
        state,
        app.id("id"));
    history(app, from, state, actor, reason);
  }

  public void history(Row app, String from, String to, UUID actor, String reason) {
    sql.update(
        "insert into"
            + " application_history(id,application_id,round,from_status,to_status,actor_id,reason,request_id)"
            + " values(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        app.id("id"),
        app.num("round"),
        from,
        to,
        actor,
        reason,
        Trace.id());
  }

  public Row participation(UUID activity, UUID user) {
    return sql.one("select * from participation where activity_id=? and user_id=?", activity, user);
  }

  public Row activate(UUID activity, UUID user, String role) {
    sql.update(
        "insert into participation(id,activity_id,user_id,role) values(?,?,?,?) on"
            + " conflict(activity_id,user_id) do update set"
            + " status='ACTIVE',confirmation='UNCONFIRMED',version=participation.version+1,joined_at=now(),left_at=null,checkin_at=null,checkin_status='NOT_REPORTED'",
        UUID.randomUUID(),
        activity,
        user,
        role);
    return participation(activity, user);
  }

  public Row ensureGroup(UUID activity) {
    sql.update(
        "insert into temporary_group(id,activity_id) values(?,?) on conflict(activity_id) do"
            + " nothing",
        UUID.randomUUID(),
        activity);
    return group(activity);
  }

  public Row group(UUID activity) {
    return sql.one("select * from temporary_group where activity_id=?", activity);
  }

  public Row groupById(UUID id) {
    return sql.must("select * from temporary_group where id=?", id);
  }

  public void release(UUID activity, UUID user, String state, Instant now) {
    sql.update(
        "update participation set status=?,left_at=?,version=version+1 where activity_id=? and"
            + " user_id=? and status='ACTIVE'",
        state,
        now,
        activity,
        user);
  }

  public void restore(UUID activity, UUID user) {
    sql.update(
        "update participation set status='LEFT',version=version+1 where activity_id=? and user_id=?"
            + " and status='REMOVED'",
        activity,
        user);
  }

  public void confirm(UUID activity, UUID user, String status) {
    sql.update(
        "update participation set confirmation=?,version=version+1 where activity_id=? and"
            + " user_id=?",
        status,
        activity,
        user);
  }

  public void reset(UUID activity) {
    sql.update(
        "update participation set confirmation='UNCONFIRMED',version=version+1 where activity_id=?"
            + " and status='ACTIVE' and role<>'OWNER'",
        activity);
  }

  public void checkin(UUID activity, UUID user, Instant now) {
    sql.update(
        "update participation set checkin_status='SELF_REPORTED',checkin_at=?,version=version+1"
            + " where activity_id=? and user_id=? and checkin_at is null",
        now,
        activity,
        user);
  }

  public void readOnly(UUID activity) {
    sql.update(
        "update temporary_group set access_mode='READ_ONLY',version=version+1 where activity_id=?"
            + " and access_mode='ACTIVE'",
        activity);
  }

  public void block(UUID activity, boolean value) {
    sql.update(
        "update temporary_group set access_mode=?,version=version+1 where activity_id=?",
        value ? "BLOCKED" : "READ_ONLY",
        activity);
  }

  public void snapshot(UUID activity, Instant at) {
    sql.update(
        "insert into completion_snapshot(activity_id,user_id,role,ended_at) select"
            + " activity_id,user_id,role,? from participation where activity_id=? and"
            + " status='ACTIVE' on conflict do nothing",
        at,
        activity);
    readOnly(activity);
  }

  public List<Row> snapshots(UUID activity) {
    return sql.list(
        "select s.*,u.nickname from completion_snapshot s join user_account u on u.id=s.user_id"
            + " where activity_id=? order by user_id",
        activity);
  }

  public List<Row> pending(UUID activity) {
    return sql.list(
        "select * from application where activity_id=? and status='PENDING' order by id for update",
        activity);
  }

  public List<UUID> affected(UUID activity) {
    return sql
        .list(
            "select user_id from participation where activity_id=? and status='ACTIVE' union select"
                + " user_id from application where activity_id=? and status='PENDING'",
            activity,
            activity)
        .stream()
        .map(r -> r.id("userId"))
        .toList();
  }

  public List<Row> applications(UUID activity) {
    return sql.list(
        "select a.*,u.nickname from application a join user_account u on u.id=a.user_id where"
            + " activity_id=? order by a.created_at desc",
        activity);
  }

  public List<Row> mine(UUID user) {
    return sql.list(
        "select"
            + " a.id,a.title,a.type,a.start_at,a.end_at,a.status,a.owner_id,a.version,a.ended_at,a.confirmation_deadline,p.status"
            + " as participation_status,p.confirmation,p.version as"
            + " participation_version,p.checkin_status,g.id as group_id,ap.id as"
            + " application_id,ap.status as application_status,ap.round,ap.version as"
            + " application_version from activity a left join participation p on p.activity_id=a.id"
            + " and p.user_id=? left join application ap on ap.activity_id=a.id and ap.user_id=?"
            + " left join temporary_group g on g.activity_id=a.id where p.id is not null or ap.id"
            + " is not null order by a.start_at desc",
        user,
        user);
  }
}
