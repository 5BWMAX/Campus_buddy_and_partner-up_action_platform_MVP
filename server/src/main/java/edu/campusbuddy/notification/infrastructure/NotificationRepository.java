package edu.campusbuddy.notification.infrastructure;

import edu.campusbuddy.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
  private final Sql sql;
  private final Json json;

  public NotificationRepository(Sql s, Json j) {
    sql = s;
    json = j;
  }

  public void record(UUID recipient, String type, UUID event, Row payload) {
    int n =
        sql.update(
            "insert into notification(id,event_id,recipient_id,type,payload)"
                + " values(?,?,?,?,?::jsonb) on conflict do nothing",
            UUID.randomUUID(),
            event,
            recipient,
            type,
            json.write(payload));
    if (n > 0)
      sql.update(
          "insert into delivery_task(id,event_id,recipient_id,payload) values(?,?,?,?::jsonb) on"
              + " conflict do nothing",
          UUID.randomUUID(),
          event,
          recipient,
          json.write(payload));
  }

  public List<Row> list(UUID user, boolean unread, Instant before, UUID last, int limit) {
    return sql.list(
        "select id,type,payload,created_at,read_at from notification where recipient_id=? "
            + (unread ? "and read_at is null " : "")
            + (before == null ? "" : "and (created_at,id)<(?,?) ")
            + "order by created_at desc,id desc limit ?",
        before == null ? new Object[] {user, limit} : new Object[] {user, before, last, limit});
  }

  public long unread(UUID user) {
    return sql.count(
        "select count(*) from notification where recipient_id=? and read_at is null", user);
  }

  public Row read(UUID user, UUID id, Instant now) {
    return sql.must(
        "update notification set read_at=coalesce(read_at,?) where id=? and recipient_id=?"
            + " returning id,read_at",
        now,
        id,
        user);
  }

  public List<Row> expired(Instant now) {
    return sql.list(
        "select * from delivery_task where status='RUNNING' and lease_until<=? for update skip"
            + " locked",
        now);
  }

  public void expire(Row task, Instant now) {
    sql.update(
        "update delivery_attempt set result='LEASE_EXPIRED' where task_id=? and attempt_no=?",
        task.id("id"),
        task.num("attempts"));
    sql.update(
        "update delivery_task set status=?,next_at=?,lease_token=null,lease_until=null where id=?",
        task.num("attempts") >= 30 ? "FAILED" : "RETRY",
        now,
        task.id("id"));
  }

  public List<Row> due(Instant now) {
    return sql.list(
        "select * from delivery_task where status in ('READY','RETRY') and next_at<=? order by"
            + " next_at limit 20 for update skip locked",
        now);
  }

  public void state(UUID id, String state) {
    sql.update("update delivery_task set status=? where id=?", state, id);
  }

  public Row activity(UUID id) {
    return sql.one("select status,version from activity where id=?", id);
  }

  public void claim(Row task, UUID lease, Instant now) {
    sql.update(
        "update delivery_task set status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?"
            + " where id=?",
        lease,
        now.plusSeconds(60),
        task.id("id"));
    sql.update(
        "insert into delivery_attempt(id,task_id,attempt_no,result) values(?,?,?,'CLAIMED')",
        UUID.randomUUID(),
        task.id("id"),
        task.num("attempts") + 1);
  }

  public boolean ownsLease(Row t, Instant now) {
    return sql.count(
            "select count(*) from delivery_task where id=? and status='RUNNING' and lease_token=?"
                + " and lease_until>?",
            t.id("id"),
            t.id("leaseToken"),
            now)
        == 1;
  }

  public void obsolete(Row t) {
    int n =
        sql.update(
            "update delivery_task set status='OBSOLETE',lease_token=null,lease_until=null where"
                + " id=? and status='RUNNING' and lease_token=?",
            t.id("id"),
            t.id("leaseToken"));
    if (n > 0)
      sql.update(
          "update delivery_attempt set result='OBSOLETE' where task_id=? and attempt_no=?",
          t.id("id"),
          t.num("attempts"));
  }

  public void finish(Row task, boolean ok, Instant next, int latency) {
    int n =
        sql.update(
            "update delivery_task set status=?,next_at=?,lease_token=null,lease_until=null where"
                + " id=? and status='RUNNING' and lease_token=?",
            ok ? "ACCEPTED" : task.num("attempts") >= 30 ? "FAILED" : "RETRY",
            next,
            task.id("id"),
            task.id("leaseToken"));
    if (n > 0)
      sql.update(
          "update delivery_attempt set result=?,latency_ms=?,error_code=? where task_id=? and"
              + " attempt_no=?",
          ok ? "ACCEPTED" : "ERROR",
          latency,
          ok ? null : "CHANNEL_UNAVAILABLE",
          task.id("id"),
          task.num("attempts"));
  }

  public List<Row> health() {
    return sql.list("select status,count(*) as count from delivery_task group by status");
  }
}
