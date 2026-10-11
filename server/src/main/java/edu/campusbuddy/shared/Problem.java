package edu.campusbuddy.shared;

public class Problem extends RuntimeException {
  public final int status;
  public final String code;

  public Problem(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static void require(boolean condition, int status, String code, String message) {
    if (!condition) throw new Problem(status, code, message);
  }

  public static Problem missing() {
    return new Problem(404, "RESOURCE_NOT_FOUND", "资源不存在或无权访问");
  }

  public static void version(long actual, long expected) {
    require(actual == expected, 409, "VERSION_CONFLICT", "内容已更新，请刷新后重试");
  }
}
