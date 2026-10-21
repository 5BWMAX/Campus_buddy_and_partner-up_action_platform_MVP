package edu.campusbuddy.notification.infrastructure;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationPreferenceRepository {
  private final Sql sql;
  private final Json json;

  // 数据库字段名 -> 前端驼峰字段名
  private static final Map<String, String> FIELD_MAP = new LinkedHashMap<>();
  static {
    FIELD_MAP.put("activity_published", "activityPublished");
    FIELD_MAP.put("application_submitted", "applicationSubmitted");
    FIELD_MAP.put("application_decided", "applicationDecided");
    FIELD_MAP.put("activity_changed", "activityChanged");
    FIELD_MAP.put("activity_cancelled", "activityCancelled");
    FIELD_MAP.put("start_reminder", "startReminder");
    FIELD_MAP.put("pending_reminder", "pendingReminder");
    FIELD_MAP.put("deadline_reached", "deadlineReached");
    FIELD_MAP.put("feedback_reminder", "feedbackReminder");
    FIELD_MAP.put("governance", "governance");
    FIELD_MAP.put("group_message", "groupMessage");
  }

  public NotificationPreferenceRepository(Sql s, Json j) {
    sql = s;
    json = j;
  }

  public Row find(UUID userId) {
    Row p = sql.one("select * from notification_preference where user_id=?", userId);
    Row out = new Row();
    out.put("userId", userId);
    if (p == null) {
      for (String camel : FIELD_MAP.values()) out.put(camel, true);
      return out;
    }
    for (Map.Entry<String, String> e : FIELD_MAP.entrySet()) {
      // Sql maps snake_case columns to camelCase Row keys.
      out.put(e.getValue(), p.bool(e.getValue()));
    }
    return out;
  }

  public Row update(UUID userId, Map<String, Object> b) {
    Row current = find(userId);
    StringBuilder sets = new StringBuilder();
    List<Object> args = new ArrayList<>();
    for (Map.Entry<String, String> e : FIELD_MAP.entrySet()) {
      String dbCol = e.getKey();
      String camel = e.getValue();
      if (b.containsKey(camel)) {
        boolean val = Boolean.parseBoolean(String.valueOf(b.get(camel)));
        if (!sets.isEmpty()) sets.append(",");
        sets.append(dbCol).append("=?");
        args.add(val);
        current.put(camel, val);
      }
    }
    if (sets.isEmpty()) return current;
    sets.append(",updated_at=now()");
    args.add(userId);
    int n =
        sql.update(
            "update notification_preference set " + sets + " where user_id=?", args.toArray());
    if (n == 0) {
      // 插入新记录
      StringBuilder cols = new StringBuilder("user_id");
      StringBuilder vals = new StringBuilder("?");
      List<Object> insertArgs = new ArrayList<>();
      insertArgs.add(userId);
      for (Map.Entry<String, String> e : FIELD_MAP.entrySet()) {
        cols.append(",").append(e.getKey());
        vals.append(",?");
        insertArgs.add(current.bool(e.getValue()));
      }
      sql.update(
          "insert into notification_preference(" + cols + ") values(" + vals + ")",
          insertArgs.toArray());
    }
    return find(userId);
  }

  public boolean enabled(UUID userId, String type) {
    Row p = find(userId);
    String camel = mapType(type);
    if (camel == null) return true;
    return p.bool(camel);
  }

  private String mapType(String type) {
    return switch (type) {
      case "ACTIVITY_PUBLISHED" -> "activityPublished";
      case "APPLICATION_SUBMITTED" -> "applicationSubmitted";
      case "APPLICATION_APPROVED",
              "APPLICATION_REJECTED",
              "APPLICATION_WITHDRAWN",
              "APPLICATION_EXPIRED",
              "APPLICATION_CANCELLED" -> "applicationDecided";
      case "ACTIVITY_CHANGED" -> "activityChanged";
      case "ACTIVITY_CANCELLED" -> "activityCancelled";
      case "START_REMINDER" -> "startReminder";
      case "PENDING_REMINDER" -> "pendingReminder";
      case "DEADLINE_REACHED" -> "deadlineReached";
      case "FEEDBACK_REQUEST", "FEEDBACK_REMINDER" -> "feedbackReminder";
      case "GOVERNANCE",
              "REPORT_SUBMITTED",
              "REPORT_UPDATED",
              "REPORT_DECIDED",
              "APPEAL_RESOLVED",
              "REPORT_SLA_OVERDUE",
              "GROUP_DISSOLVED",
              "GROUP_RESTORED" -> "governance";
      case "GROUP_MESSAGE" -> "groupMessage";
      default -> null;
    };
  }
}
