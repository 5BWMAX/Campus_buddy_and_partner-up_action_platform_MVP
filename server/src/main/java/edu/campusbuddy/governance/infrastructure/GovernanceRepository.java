package edu.campusbuddy.governance.infrastructure;

import edu.campusbuddy.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class GovernanceRepository {
  private final Sql sql;
  private final Json json;

  public GovernanceRepository(Sql s, Json j) {
    sql = s;
    json = j;
  }

  public List<Row> recordOverdue(Instant now) {
    return sql.list(
        "insert into governance_sla_event(report_id,deadline_at,recorded_at) "
            + "select id,created_at+interval '24 hours',? from report "
            + "where created_at+interval '24 hours'<=? "
            + "and (accepted_at is null or accepted_at>=created_at+interval '24 hours') "
            + "on conflict(report_id) do nothing returning *",
        now,
        now);
  }

  public Row report(UUID id, boolean lock) {
    return sql.must("select * from report where id=?" + (lock ? " for update" : ""), id);
  }

  public UUID report(UUID actor, String type, UUID target, String reason, String evidence) {
    UUID id = UUID.randomUUID();
    sql.update(
        "insert into report(id,reporter_id,target_type,target_id,reason,evidence)"
            + " values(?,?,?,?,?,?)",
        id,
        actor,
        type,
        target,
        reason,
        evidence);
    return id;
  }

  public void phase(UUID id, String phase, Instant accepted) {
    sql.update(
        "update report set status=?,accepted_at=coalesce(accepted_at,?),version=version+1 where"
            + " id=?",
        phase,
        accepted,
        id);
  }

  public void decision(UUID id, UUID disposition, Row d) {
    sql.update(
        "update report set status='RESOLVED',decision=?::jsonb,disposition_id=?,version=version+1"
            + " where id=?",
        json.write(d),
        disposition,
        id);
  }

  public void audit(UUID actor, String action, UUID resource, String reason, Row details) {
    sql.update(
        "insert into audit_log(id,actor_id,action,resource_id,reason,request_id,details)"
            + " values(?,?,?,?,?,?,?::jsonb)",
        UUID.randomUUID(),
        actor,
        action,
        resource,
        reason,
        Trace.id(),
        json.write(details));
  }

  public List<Row> reports(UUID actor, boolean admin) {
    return sql.list(
        "select * from report "
            + (admin
                ? ""
                : "where reporter_id=? or (target_type='USER' and target_id=?) or"
                    + " (target_type='ACTIVITY' and target_id in(select id from activity where"
                    + " owner_id=?)) or (target_type='MESSAGE' and target_id in(select id from"
                    + " message where sender_id=?)) ")
            + "order by created_at desc",
        admin ? new Object[] {} : new Object[] {actor, actor, actor, actor});
  }

  public List<Row> appeals(UUID report) {
    return sql.list("select * from appeal where report_id=? order by created_at", report);
  }

  public Row appeal(UUID id, boolean lock) {
    return sql.must("select * from appeal where id=?" + (lock ? " for update" : ""), id);
  }

  public UUID appeal(UUID report, UUID user, String reason, String evidence) {
    UUID id = UUID.randomUUID();
    int n =
        sql.update(
            "insert into appeal(id,report_id,appellant_id,reason,evidence) values(?,?,?,?,?) on"
                + " conflict(report_id,appellant_id) do nothing",
            id,
            report,
            user,
            reason,
            evidence);
    Problem.require(n == 1, 409, "ALREADY_APPEALED", "已经提交过申诉");
    phase(report, "APPEAL_PENDING", null);
    return id;
  }

  public void resolveAppeal(UUID id, String status, String reason) {
    sql.update(
        "update appeal set status=?,result=?,version=version+1 where id=?", status, reason, id);
  }

  public void resolveReport(UUID id, Row decision) {
    sql.update(
        "update report set status='APPEAL_RESOLVED',decision=?::jsonb,version=version+1 where id=?",
        json.write(decision),
        id);
  }

  public long otherSanctions(UUID report, String action, UUID target) {
    return sql.count(
        "select count(*) from report where id<>? and target_id=? and decision->>'action'=? and"
            + " coalesce((decision->>'revoked')::boolean,false)=false and status in"
            + " ('RESOLVED','APPEAL_PENDING','APPEAL_RESOLVED')",
        report,
        target,
        action);
  }

  public List<Row> audit() {
    return sql.list("select * from audit_log order by created_at desc limit 200");
  }

  public Row statistics(Instant from, Instant to, String type, Instant now) {
    String filter = " and (?='' or a.type=?)";
    Object[] args = {from, to, type, type};
    long created =
        sql.count(
            "select count(*) from activity a where a.created_at>=? and a.created_at<?" + filter,
            args);
    long submissions =
        sql.count(
            "select count(*) from application_history h join application ap on"
                + " ap.id=h.application_id join activity a on a.id=ap.activity_id where"
                + " h.to_status='PENDING' and a.created_at>=? and a.created_at<?"
                + filter,
            args);
    long approvals =
        sql.count(
            "select count(*) from application_history h join application ap on"
                + " ap.id=h.application_id join activity a on a.id=ap.activity_id where"
                + " h.to_status='APPROVED' and a.created_at>=? and a.created_at<?"
                + filter,
            args);
    Object[] endArgs = {from, to, now, type, type};
    String endedWhere = " where a.end_at>=? and a.end_at<? and a.end_at<=?" + filter;
    long due = sql.count("select count(*) from activity a" + endedWhere, endArgs);
    long ended =
        sql.count(
            "select count(*) from activity a"
                + endedWhere
                + " and (a.status='ENDED' or a.terminal_state='ENDED')",
            endArgs);
    long cancelled =
        sql.count(
            "select count(*) from activity a"
                + endedWhere
                + " and (a.status='CANCELLED' or a.terminal_state='CANCELLED')",
            endArgs);
    long snapshots =
        sql.count(
            "select count(*) from completion_snapshot s join activity a on a.id=s.activity_id"
                + endedWhere,
            endArgs);
    long completed =
        sql.count(
            "select count(*) from feedback f join activity a on a.id=f.activity_id"
                + endedWhere
                + " and f.completion='COMPLETED'",
            endArgs);
    long noShows =
        sql.count(
            "select count(*) from (select distinct r.decision->>'activityId',r.decision->>'userId'"
                + " from report r join activity a on a.id=(r.decision->>'activityId')::uuid join"
                + " completion_snapshot s on s.activity_id=a.id and"
                + " s.user_id=(r.decision->>'userId')::uuid"
                + endedWhere
                + " and r.decision->>'finding'='NO_SHOW' and"
                + " coalesce((r.decision->>'revoked')::boolean,false)=false) pairs",
            endArgs);
    String reportWhere =
        " where r.created_at>=? and r.created_at<? and (?='' or exists(select 1 from activity a"
            + " where a.id=case when r.target_type='ACTIVITY' then r.target_id else"
            + " (r.decision->>'activityId')::uuid end and a.type=?))";
    long reports = sql.count("select count(*) from report r" + reportWhere, args);
    List<Object> lateArgs = new ArrayList<>(List.of(args));
    lateArgs.add(now);
    long late =
        sql.count(
            "select count(*) from report r"
                + reportWhere
                + " and coalesce(r.accepted_at,?)>=r.created_at+interval '24 hours'",
            lateArgs.toArray());
    return Row.of(
        "from",
        from,
        "to",
        to,
        "asOf",
        now,
        "type",
        type,
        "published",
        created,
        "submittedRounds",
        submissions,
        "approvedRounds",
        approvals,
        "applicationConversion",
        ratio(approvals, submissions),
        "dueActivities",
        due,
        "endedActivities",
        ended,
        "cancelledActivities",
        cancelled,
        "completionRate",
        ratio(ended, due),
        "snapshotMembers",
        snapshots,
        "completedFeedback",
        completed,
        "feedbackRate",
        ratio(completed, snapshots),
        "confirmedNoShows",
        noShows,
        "noShowRate",
        ratio(noShows, snapshots),
        "reports",
        reports,
        "lateReports",
        late,
        "reportOverdueRate",
        ratio(late, reports));
  }

  private Double ratio(long n, long d) {
    return d == 0 ? null : (double) n / d;
  }
}
