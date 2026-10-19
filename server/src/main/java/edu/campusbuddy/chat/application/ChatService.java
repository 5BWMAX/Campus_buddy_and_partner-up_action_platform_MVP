package edu.campusbuddy.chat.application;

import edu.campusbuddy.chat.api.*;
import edu.campusbuddy.chat.infrastructure.*;
import edu.campusbuddy.notification.api.*;
import edu.campusbuddy.shared.*;
import edu.campusbuddy.team.api.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ChatService implements ChatApi {
  private static final Pattern UPLOADED_IMAGE =
      Pattern.compile("^/uploads/[0-9a-f]{32}\\.(jpg|png|gif|webp)$");

  private final ChatRepository repo;
  private final TeamApi team;
  private final NotificationApi notices;
  private final Json json;
  private final Cursor cursor;
  private final Clock clock;

  public ChatService(
      ChatRepository r, TeamApi t, NotificationApi n, Json j, Cursor c, Clock cl) {
    repo = r;
    team = t;
    notices = n;
    json = j;
    cursor = c;
    clock = cl;
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
    Row grant = team.grant(actor, group, true);
    UUID client = Input.uuid(b.get("clientMessageId"));
    String mediaType = b.get("mediaType") == null ? null : String.valueOf(b.get("mediaType"));
    String mediaUrl = b.get("mediaUrl") == null ? null : String.valueOf(b.get("mediaUrl"));
    String body = null;
    String hash;
    if (mediaType != null || mediaUrl != null) {
      Problem.require(
          "IMAGE".equals(mediaType) && mediaUrl != null,
          400,
          "VALIDATION_FAILED",
          "图片消息需要 mediaType=IMAGE 和 mediaUrl");
      Problem.require(
          UPLOADED_IMAGE.matcher(mediaUrl).matches(),
          400,
          "INVALID_MEDIA",
          "图片地址不合法，请先通过上传接口上传");
      String fileName = mediaUrl.substring("/uploads/".length());
      Problem.require(
          repo.mediaOwnedBy(actor, fileName),
          400,
          "INVALID_MEDIA",
          "只能发送本人上传的图片");
      hash = json.hash(mediaType + ":" + mediaUrl);
    } else {
      body = Input.text(b, "body", 1000, true);
      hash = json.hash(body);
    }
    Row previous = repo.existing(group, actor, client);
    if (previous != null) {
      Problem.require(hash.equals(previous.text("requestHash")), 409, "KEY_REUSED", "消息标识已用于其他内容");
      return previous.only("id", "createdAt");
    }
    Row inserted = repo.insert(group, actor, client, body, mediaType, mediaUrl, hash);
    if (inserted == null) {
      // A concurrent request with the same clientMessageId won the unique constraint.
      Row current = repo.existing(group, actor, client);
      Problem.require(current != null, 409, "MESSAGE_CONFLICT", "消息标识冲突，请重试");
      Problem.require(
          hash.equals(current.text("requestHash")), 409, "KEY_REUSED", "消息标识已用于其他内容");
      return current.only("id", "createdAt");
    }

    UUID activity = grant.id("activityId");
    String preview = mediaType == null ? "群里有新消息" : "有人发来一张图片";
    for (UUID member : team.activeUsers(activity))
      if (!member.equals(actor))
        notices.record(
            member,
            activity,
            "GROUP_MESSAGE",
            inserted.id("id") + ":group:" + member,
            Row.of("message", preview, "groupId", group, "messageId", inserted.id("id")));
    return inserted.only("id", "createdAt");
  }

  @Transactional
  public Row recall(UUID actor, UUID id) {
    Row m = repo.message(id);
    Problem.require(
        actor.equals(m.id("senderId")), 403, "FORBIDDEN", "仅发送者可撤回消息");
    Instant createdAt = m.time("createdAt");
    Instant deadline = createdAt.plusSeconds(120);
    Problem.require(
        clock.instant().isBefore(deadline),
        409,
        "RECALL_EXPIRED",
        "消息发送已超过2分钟，无法撤回");
    int n = repo.recall(id);
    Problem.require(n > 0, 409, "ALREADY_RECALLED", "消息已撤回");
    team.grant(actor, m.id("groupId"), false);
    return Row.of("id", id, "recalled", true);
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
