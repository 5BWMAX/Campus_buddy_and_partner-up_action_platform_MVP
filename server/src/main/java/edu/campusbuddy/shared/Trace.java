package edu.campusbuddy.shared;

import java.util.UUID;
import org.slf4j.MDC;

public final class Trace {
  public static String id() {
    String v = MDC.get("requestId");
    return v == null ? UUID.randomUUID().toString() : v;
  }
}
