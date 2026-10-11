package edu.campusbuddy.shared;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.ResponseEntity;

public final class HttpSupport {
  public static UUID actor(HttpServletRequest req) {
    HttpSession s = req.getSession(false);
    Object id = s == null ? null : s.getAttribute("actor");
    Problem.require(id != null, 401, "AUTH_REQUIRED", "请先登录");
    return UUID.fromString(id.toString());
  }

  public static UUID optional(HttpServletRequest req) {
    try {
      return actor(req);
    } catch (Problem e) {
      return null;
    }
  }

  public static UUID key(HttpServletRequest req) {
    String s = req.getHeader("Idempotency-Key");
    Problem.require(s != null, 400, "IDEMPOTENCY_KEY_REQUIRED", "需要Idempotency-Key请求标识");
    return Input.uuid(s);
  }

  public static ResponseEntity<Row> ok(Object data) {
    return response(data, 200);
  }

  public static ResponseEntity<Row> created(Object data) {
    return response(data, 201);
  }

  public static ResponseEntity<Row> response(Object data, int status) {
    return ResponseEntity.status(status).body(Row.of("data", data, "requestId", Trace.id()));
  }
}
