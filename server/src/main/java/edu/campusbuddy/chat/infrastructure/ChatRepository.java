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

  public Row insert(UUID group, UUID actor, UUID client, String body, String mediaType, String mediaUrl, String hash) {
    UUID id = UUID.randomUUID();
    // A duplicate clientMessageId must never abort the PostgreSQL transaction. The unique
    // constraint is the final arbiter; the caller then replays the existing row or rejects
    // a different payload.
    int inserted =
        sql.update(
            "insert into message(id,group_id,sender_id,client_message_id,body,media_type,media_url,request_hash)"
                + " values(?,?,?,?,?,?,?,?) on conflict(group_id,sender_id,client_message_id) do nothing",
            id,
            group,
            actor,
            client,
            body,
            mediaType,
            mediaUrl,
            hash);
    return inserted == 0 ? null : message(id);
  }

  public List<Row> messages(UUID group, Instant at, UUID id, int size) {
    return sql.list(
        "select m.id,m.sender_id,u.nickname,"
            + "case when m.recalled_at is null then m.body else null end as body,"
            + "case when m.recalled_at is null then m.media_type else null end as media_type,"
            + "case when m.recalled_at is null then m.media_url else null end as media_url,"
            + "m.recalled_at,m.created_at from message m join user_account u"
            + " on u.id=m.sender_id where group_id=? and hidden=false "
            + (at == null ? "" : "and (m.created_at,m.id)<(?,?) ")
            + "order by m.created_at desc,m.id desc limit ?",
        at == null ? new Object[] {group, size} : new Object[] {group, at, id, size});
  }

  public boolean mediaOwnedBy(UUID uploader, String storagePath) {
    return sql.count(
            "select count(*) from media_file where uploader_id=? and storage_path=?",
            uploader,
            storagePath)
        > 0;
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

  // ===== 消息撤回 =====
  public int recall(UUID id) {
    return sql.update(
        "update message set recalled_at=now(),body=null,media_type=null,media_url=null"
            + " where id=? and recalled_at is null",
        id);
  }
}
