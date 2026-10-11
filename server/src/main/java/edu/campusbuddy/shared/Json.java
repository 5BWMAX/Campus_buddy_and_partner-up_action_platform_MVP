package edu.campusbuddy.shared;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import org.springframework.stereotype.Component;

@Component
public class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper m) {
    mapper = m.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  public String write(Object v) {
    try {
      return mapper.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public Object read(String v) {
    try {
      return mapper.readValue(v, Object.class);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public Row row(String v) {
    return new Row((java.util.Map<String, Object>) read(v));
  }

  public String hash(Object v) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(write(v).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
