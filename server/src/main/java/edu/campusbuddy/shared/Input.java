package edu.campusbuddy.shared;

import java.time.*;
import java.util.*;

public final class Input {
  public static String text(Map<String, Object> b, String k, int max, boolean required) {
    Object v = b.get(k);
    Problem.require(v == null || v instanceof String, 400, "VALIDATION_FAILED", k + "必须是文字");
    String s = v == null ? "" : v.toString().trim();
    Problem.require(
        (!required || !s.isBlank()) && s.length() <= max,
        400,
        "VALIDATION_FAILED",
        k + "必填或长度超限（最多" + max + "字）");
    return s;
  }

  public static long number(Map<String, Object> b, String k, long min, long max) {
    Object o = b.get(k);
    Problem.require(o instanceof Number, 400, "VALIDATION_FAILED", k + "必须是整数");
    double d = ((Number) o).doubleValue();
    Problem.require(
        Double.isFinite(d) && d == Math.floor(d) && d >= min && d <= max,
        400,
        "VALIDATION_FAILED",
        k + "数值不合法");
    return ((Number) o).longValue();
  }

  public static UUID uuid(Object v) {
    try {
      return UUID.fromString(String.valueOf(v));
    } catch (Exception e) {
      throw new Problem(400, "VALIDATION_FAILED", "UUID不合法");
    }
  }

  public static Instant time(Map<String, Object> b, String k) {
    try {
      return Instant.parse(String.valueOf(b.get(k)));
    } catch (Exception e) {
      throw new Problem(400, "VALIDATION_FAILED", k + "需要带时区的ISO时间");
    }
  }

  public static String choice(Map<String, Object> b, String k, String... values) {
    String s = text(b, k, 40, true);
    Problem.require(Arrays.asList(values).contains(s), 400, "VALIDATION_FAILED", k + "选项不合法");
    return s;
  }

  public static List<String> tags(Object o) {
    if (o == null) return List.of();
    Problem.require(o instanceof List<?>, 400, "VALIDATION_FAILED", "tags必须是数组");
    List<?> a = (List<?>) o;
    Problem.require(a.size() <= 10, 400, "VALIDATION_FAILED", "最多10个标签");
    return a.stream()
        .map(
            v -> {
              Problem.require(
                  v instanceof String && !v.toString().isBlank() && v.toString().length() <= 30,
                  400,
                  "VALIDATION_FAILED",
                  "标签1至30字");
              return v.toString().trim();
            })
        .distinct()
        .toList();
  }
}
