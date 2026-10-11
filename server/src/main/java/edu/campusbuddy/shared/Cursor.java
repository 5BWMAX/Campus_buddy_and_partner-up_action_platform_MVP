package edu.campusbuddy.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Cursor {
  private final Json json;
  private final byte[] key;

  public Cursor(Json j, @Value("${campus.cursor-key}") String k) {
    json = j;
    key = Base64.getDecoder().decode(k);
  }

  private byte[] sign(String s) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(key, "HmacSHA256"));
      return m.doFinal(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String encode(Row r, String context) {
    Row data = new Row(r);
    data.put("context", context);
    String raw =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.write(data).getBytes(StandardCharsets.UTF_8));
    return raw + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(raw));
  }

  public Row decode(String s, String context) {
    if (s == null || s.isBlank()) return null;
    try {
      String[] p = s.split("\\.");
      if (p.length != 2 || !MessageDigest.isEqual(sign(p[0]), Base64.getUrlDecoder().decode(p[1])))
        throw new IllegalArgumentException();
      Row r = json.row(new String(Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8));
      if (!context.equals(r.text("context"))) throw new IllegalArgumentException();
      return r;
    } catch (Exception e) {
      throw new Problem(400, "CURSOR_INVALID", "游标失效或筛选条件已变更，请重新查询");
    }
  }

  public Row page(List<Row> items, int size, String context, String at) {
    boolean more = items.size() > size;
    List<Row> result = more ? items.subList(0, size) : items;
    String next = null;
    if (more) {
      Row last = result.getLast();
      next = encode(Row.of("at", last.get(at), "id", last.get("id")), context);
    }
    return Row.of("items", result, "hasMore", more, "nextCursor", next);
  }

  public static int size(Object o) {
    try {
      int n = o == null ? 20 : Integer.parseInt(o.toString());
      Problem.require(n >= 1 && n <= 50, 400, "VALIDATION_FAILED", "pageSize应为1至50");
      return n;
    } catch (NumberFormatException e) {
      throw new Problem(400, "VALIDATION_FAILED", "pageSize不是整数");
    }
  }
}
