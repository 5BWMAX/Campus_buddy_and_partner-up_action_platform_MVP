package edu.campusbuddy.shared;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Sql {
  private final JdbcTemplate jdbc;
  private final Json json;

  public Sql(JdbcTemplate j, Json x) {
    jdbc = j;
    json = x;
  }

  private Object[] args(Object[] a) {
    return Arrays.stream(a).map(v -> v instanceof Instant i ? Timestamp.from(i) : v).toArray();
  }

  public List<Row> list(String q, Object... a) {
    return jdbc.query(
        q,
        (rs, n) -> {
          Row r = new Row();
          var m = rs.getMetaData();
          for (int i = 1; i <= m.getColumnCount(); i++) {
            String k = m.getColumnLabel(i);
            var b = new StringBuilder();
            boolean cap = false;
            for (char c : k.toCharArray()) {
              if (c == '_') {
                cap = true;
              } else {
                b.append(cap ? Character.toUpperCase(c) : c);
                cap = false;
              }
            }
            Object v = rs.getObject(i);
            if (v instanceof Timestamp t) v = t.toInstant();
            if (v instanceof PGobject p
                && (p.getType().equals("jsonb") || p.getType().equals("json")))
              v = json.read(p.getValue());
            r.put(b.toString(), v);
          }
          return r;
        },
        args(a));
  }

  public Row one(String q, Object... a) {
    var rows = list(q, a);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Row must(String q, Object... a) {
    Row r = one(q, a);
    if (r == null) throw Problem.missing();
    return r;
  }

  public long count(String q, Object... a) {
    return ((Number) must(q, a).values().iterator().next()).longValue();
  }

  public int update(String q, Object... a) {
    return jdbc.update(q, args(a));
  }
}
