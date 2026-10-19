package edu.campusbuddy.notification.application;

import edu.campusbuddy.notification.api.*;
import edu.campusbuddy.notification.infrastructure.*;
import edu.campusbuddy.shared.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class NotificationService implements NotificationApi {
  private final NotificationRepository repo;
  private final NotificationPreferenceRepository prefs;
  private final Clock clock;
  private final Cursor cursor;

  public NotificationService(NotificationRepository r, NotificationPreferenceRepository p, Clock c, Cursor page) {
    repo = r;
    prefs = p;
    clock = c;
    cursor = page;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(UUID user, UUID activity, String type, String key, Row data) {
    // 检查用户通知偏好
    if (!prefs.enabled(user, type)) return;
    Row payload = new Row(data);
    payload.put("activityId", activity);
    payload.put("schemaVersion", 1);
    payload.put("occurredAt", clock.instant());
    payload.put("template", type);
    repo.record(user, type, UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), payload);
  }

  public Row preferences(UUID user) {
    return prefs.find(user);
  }

  @Transactional
  public Row updatePreferences(UUID user, Map<String, Object> b) {
    return prefs.update(user, b);
  }

  public Row list(UUID user, boolean unread, String token, int size) {
    Row pos = cursor.decode(token, "notifications:" + user + ":" + unread);
    var items =
        repo.list(
            user,
            unread,
            pos == null ? null : pos.time("at"),
            pos == null ? null : pos.id("id"),
            size + 1);
    Row page = cursor.page(items, size, "notifications:" + user + ":" + unread, "createdAt");
    page.put("unreadCount", repo.unread(user));
    return page;
  }

  @Transactional
  public Row read(UUID user, UUID id) {
    return repo.read(user, id, clock.instant());
  }

  public List<Row> health() {
    return repo.health();
  }
}
