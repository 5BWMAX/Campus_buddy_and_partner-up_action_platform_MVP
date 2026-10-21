package edu.campusbuddy.identity.infrastructure;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
  private final Sql sql;

  public IdentityRepository(Sql s) {
    sql = s;
  }

  public Row user(UUID id) {
    return sql.must(
        "select u.*,p.contact_visibility,p.location_visibility from user_account u join"
            + " privacy_setting p on p.user_id=u.id where u.id=?",
        id);
  }

  public List<Row> users(Collection<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    String parameters = String.join(",", Collections.nCopies(ids.size(), "?"));
    return sql.list(
        "select u.*,p.contact_visibility,p.location_visibility from user_account u join"
            + " privacy_setting p on p.user_id=u.id where u.id in ("
            + parameters
            + ")",
        ids.toArray());
  }

  public void lock(UUID id) {
    sql.must("select id from user_account where id=? for update", id);
  }

  public void lockReference(UUID id) {
    sql.must("select id from user_account where id=? for key share", id);
  }

  public boolean exists(UUID id) {
    return sql.count("select count(*) from user_account where id=? and status<>'DELETED'", id) > 0;
  }

  public void profile(UUID id, String nickname, String avatar, String dept, String contact) {
    sql.update(
        "update user_account set"
            + " nickname=?,avatar_url=?,department=?,contact_ciphertext=?,updated_at=now() where"
            + " id=?",
        nickname,
        avatar,
        dept,
        contact,
        id);
  }

  public void privacy(UUID id, String contact, String location) {
    sql.update(
        "update privacy_setting set contact_visibility=?,location_visibility=? where user_id=?",
        contact,
        location,
        id);
  }

  public void status(UUID id, String status) {
    // Reversing a governance decision must never resurrect an erased account.
    sql.update("update user_account set status=? where id=? and status<>'DELETED'", status, id);
    revokeSessions(id);
  }

  public void revokeSessions(UUID id) {
    sql.update("delete from spring_session where principal_name=?", id.toString());
  }

  public void erase(UUID id) {
    sql.update("delete from account_credential where user_id=?", id);
    sql.update(
        "update user_account set"
            + " status='DELETED',campus_subject=null,nickname='已注销用户',avatar_url=null,department=null,contact_ciphertext=null,verified=false"
            + " where id=?",
        id);
    privacy(id, "HIDDEN", "HIDDEN");
    // V3 user-owned auxiliary data must disappear with the account. Participation,
    // feedback and governance history stay as anonymised records under the retained id.
    sql.update("delete from search_history where user_id=?", id);
    sql.update("delete from activity_favorite where user_id=?", id);
    sql.update("delete from notification_preference where user_id=?", id);
    sql.update(
        "delete from delivery_attempt where task_id in (select id from delivery_task where recipient_id=?)",
        id);
    sql.update("delete from delivery_task where recipient_id=?", id);
    sql.update("delete from notification where recipient_id=?", id);
    sql.update("delete from idempotency_record where actor_id=?", id);
    sql.update("insert into erasure_log(user_id) values(?) on conflict do nothing", id);
    revokeSessions(id);
  }

  public List<UUID> administrators() {
    return sql.list("select id from user_account where role='ADMIN' and status='NORMAL'").stream()
        .map(r -> r.id("id"))
        .toList();
  }

  public Row credentialState(UUID id) {
    return sql.one("select c.username,c.password_change_required from account_credential c join user_account u on u.id=c.user_id where c.user_id=? and u.demo=false and u.status<>'DELETED'", id);
  }

  public List<Row> demoUsers() {
    return sql.list(
        "select id,nickname,role,verified,status from user_account where demo=true and"
            + " status<>'DELETED' order by role,nickname");
  }

  public void seed(UUID id, String name, String role, boolean verified) {
    sql.update(
        "insert into user_account(id,campus_subject,nickname,role,verified,demo,department)"
            + " values(?,?,?,?,?,true,'测试学院') on conflict do nothing",
        id,
        "demo:" + id,
        name,
        role,
        verified);
    sql.update("insert into privacy_setting(user_id) values(?) on conflict do nothing", id);
  }
}
