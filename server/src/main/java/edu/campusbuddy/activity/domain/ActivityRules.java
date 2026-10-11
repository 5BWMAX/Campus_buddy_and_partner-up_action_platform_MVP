package edu.campusbuddy.activity.domain;

import edu.campusbuddy.shared.*;
import java.time.*;

public final class ActivityRules {
  public static void validate(Row a, Instant now) {
    Problem.require(
        a.time("deadline").compareTo(a.time("confirmationDeadline")) <= 0
            && a.time("confirmationDeadline").compareTo(a.time("startAt")) <= 0
            && a.time("startAt").isBefore(a.time("endAt")),
        400,
        "VALIDATION_FAILED",
        "时间需满足：报名截止≤确认截止≤开始<结束");
    Problem.require(a.time("startAt").isAfter(now), 409, "DEADLINE_PASSED", "活动开始时间必须在未来");
  }

  public static void open(Row a, Instant now) {
    Problem.require(
        a.text("status").equals("OPEN")
            && a.text("visibility").equals("VISIBLE")
            && now.isBefore(a.time("deadline")),
        409,
        "DEADLINE_PASSED",
        "活动已截止、结束或被屏蔽");
  }

  public static void editable(Row a, Instant now) {
    Problem.require(
        a.text("status").equals("OPEN") && now.isBefore(a.time("startAt")),
        409,
        "ACTIVITY_NOT_EDITABLE",
        "活动开始后不能修改");
  }

  public static boolean overlap(Instant a, Instant b, Instant c, Instant d) {
    return a.isBefore(d) && b.isAfter(c);
  }
}
