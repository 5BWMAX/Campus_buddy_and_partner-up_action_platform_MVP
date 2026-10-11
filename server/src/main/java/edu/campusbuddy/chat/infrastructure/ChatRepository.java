package edu.campusbuddy.chat.infrastructure;

import edu.campusbuddy.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class ChatRepository {
  private final Sql sql;

  public ChatRepository(Sql s) {
    sql = s;
  }

  public Row message(UUID id) {
    return sql.must("select * from message where id=?", id);
  }

  public Row existing(UUID group, UUID actor, UUID client) {
    return sql.one(
        "select * from message where group_id=? and sender_id=? and client_message_id=?",
        group,
        actor,
        client);
  }

  public Row insert(UUID group, UUID actor, UUID client, String body, String hash) {
    UUID id = UUID.randomUUID();
    sql.update(
        "insert into message(id,group_id,sender_id,client_message_id,body,request_hash)"
            + " values(?,?,?,?,?,?)",
        id,
        group,
        actor,
        client,
        body,
        hash);
    return message(id);
  }

  public List<Row> messages(UUID group, Instant at, UUID id, int size) {
    return sql.list(
        "select m.id,m.sender_id,u.nickname,m.body,m.created_at from message m join user_account u"
            + " on u.id=m.sender_id where group_id=? and hidden=false "
            + (at == null ? "" : "and (m.created_at,m.id)<(?,?) ")
            + "order by m.created_at desc,m.id desc limit ?",
        at == null ? new Object[] {group, size} : new Object[] {group, at, id, size});
  }

  public Row pin(UUID group) {
    Row p = sql.one("select * from group_pin where group_id=?", group);
    return p == null ? Row.of("groupId", group, "body", "", "version", 0) : p;
  }

  public Row pin(UUID group, UUID actor, String body, long version) {
    Row p = pin(group);
    Problem.version(p.num("version"), version);
    sql.update(
        "insert into group_pin(group_id,editor_id,body,version) values(?,?,?,1) on"
            + " conflict(group_id) do update set"
            + " editor_id=excluded.editor_id,body=excluded.body,version=group_pin.version+1",
        group,
        actor,
        body);
    return pin(group);
  }

  public void hide(UUID id, boolean hidden) {
    sql.update("update message set hidden=? where id=?", hidden, id);
  }
}
