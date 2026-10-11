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

  public void lock(UUID id) {
    sql.must("select id from user_account where id=? for update", id);
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
    sql.update(
        "update user_account set"
            + " status='DELETED',campus_subject=null,nickname='已注销用户',avatar_url=null,department=null,contact_ciphertext=null,verified=false"
            + " where id=?",
        id);
    privacy(id, "HIDDEN", "HIDDEN");
    sql.update("insert into erasure_log(user_id) values(?) on conflict do nothing", id);
    revokeSessions(id);
  }

  public List<UUID> administrators() {
    return sql.list("select id from user_account where role='ADMIN' and status='NORMAL'").stream()
        .map(r -> r.id("id"))
        .toList();
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
