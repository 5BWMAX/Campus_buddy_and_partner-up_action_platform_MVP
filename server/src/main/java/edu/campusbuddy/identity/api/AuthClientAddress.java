package edu.campusbuddy.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthClientAddress {
  private final boolean trustProxy;

  public AuthClientAddress(@Value("${campus.auth.trust-loopback-proxy:false}") boolean trustProxy) {
    this.trustProxy = trustProxy;
  }

  public String client(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    if (!trustProxy || !java.util.Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(peer)) return peer;
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null) return peer;
    // Accept exactly one literal IP, never a proxy chain or a hostname requiring DNS.
    if (forwarded.contains(":")) {
      if (!forwarded.matches("[0-9a-fA-F:.]{2,45}")) return peer;
    } else {
      if (!forwarded.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return peer;
      for (String part : forwarded.split("\\.")) if (Integer.parseInt(part) > 255) return peer;
    }
    try {
      return InetAddress.getByName(forwarded).getHostAddress();
    } catch (java.net.UnknownHostException e) {
      return peer;
    }
  }
}
