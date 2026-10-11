package edu.campusbuddy.shared;

import java.time.*;
import java.util.*;

public class Row extends LinkedHashMap<String, Object> {
  public Row() {}

  public Row(Map<String, ?> m) {
    super(m);
  }

  public static Row of(Object... kv) {
    Row r = new Row();
    for (int i = 0; i < kv.length; i += 2) r.put(kv[i].toString(), kv[i + 1]);
    return r;
  }

  public String text(String k) {
    Object v = get(k);
    return v == null ? "" : v.toString();
  }

  public UUID id(String k) {
    Object v = get(k);
    return v == null ? null : UUID.fromString(v.toString());
  }

  public long num(String k) {
    Object v = get(k);
    return v == null ? 0 : ((Number) v).longValue();
  }

  public boolean bool(String k) {
    return Boolean.TRUE.equals(get(k));
  }

  public Instant time(String k) {
    Object v = get(k);
    return v == null ? null : (v instanceof Instant i ? i : Instant.parse(v.toString()));
  }

  public Row only(String... keys) {
    Row r = new Row();
    for (String k : keys) r.put(k, get(k));
    return r;
  }
}
