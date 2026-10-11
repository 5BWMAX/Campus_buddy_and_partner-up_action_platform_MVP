package edu.campusbuddy.chat.application;

import edu.campusbuddy.chat.api.*;
import edu.campusbuddy.chat.infrastructure.*;
import edu.campusbuddy.shared.*;
import edu.campusbuddy.team.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ChatService implements ChatApi {
  private final ChatRepository repo;
  private final TeamApi team;
  private final Json json;
  private final Cursor cursor;

  public ChatService(ChatRepository r, TeamApi t, Json j, Cursor c) {
    repo = r;
    team = t;
    json = j;
    cursor = c;
  }

  @Transactional
  public Row messages(UUID actor, UUID group, String token, int size) {
    Row grant = team.grant(actor, group, false);
    String context = "messages:" + group;
    Row pos = cursor.decode(token, context);
    Row page =
        cursor.page(
            repo.messages(
                group,
                pos == null ? null : pos.time("at"),
                pos == null ? null : pos.id("id"),
                size + 1),
            size,
            context,
            "createdAt");
    page.put("pin", repo.pin(group));
    page.put("accessMode", grant.get("accessMode"));
    page.put("ownerId", grant.get("ownerId"));
    return page;
  }

  @Transactional
  public Row send(UUID actor, UUID group, Map<String, Object> b) {
    team.grant(actor, group, true);
    UUID client = Input.uuid(b.get("clientMessageId"));
    String body = Input.text(b, "body", 1000, true), hash = json.hash(body);
    Row previous = repo.existing(group, actor, client);
    if (previous != null) {
      Problem.require(hash.equals(previous.text("requestHash")), 409, "KEY_REUSED", "消息标识已用于其他内容");
      return previous.only("id", "createdAt");
    }
    return repo.insert(group, actor, client, body, hash).only("id", "createdAt");
  }

  @Transactional
  public Row pin(UUID actor, UUID group, Map<String, Object> b) {
    Row grant = team.grant(actor, group, true);
    Problem.require(actor.equals(grant.id("ownerId")), 403, "FORBIDDEN", "仅发起人可编辑置顶");
    return repo.pin(
        group,
        actor,
        Input.text(b, "body", 2000, true),
        Input.number(b, "expectedVersion", 0, Long.MAX_VALUE));
  }

  @Transactional
  public UUID requireReportable(UUID actor, UUID id) {
    Row m = repo.message(id);
    team.grant(actor, m.id("groupId"), false);
    return m.id("senderId");
  }

  public UUID author(UUID id) {
    return repo.message(id).id("senderId");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void hide(UUID id, boolean hidden) {
    repo.hide(id, hidden);
  }
}
