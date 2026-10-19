package edu.campusbuddy.notification.api;

import edu.campusbuddy.identity.api.*;
import edu.campusbuddy.notification.application.*;
import edu.campusbuddy.shared.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
  private final NotificationService service;
  private final IdentityApi identity;

  public NotificationController(NotificationService s, IdentityApi i) {
    service = s;
    identity = i;
  }

  @GetMapping("/notifications")
  public ResponseEntity<Row> list(
      HttpServletRequest r,
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer pageSize) {
    UUID actor = HttpSupport.actor(r);
    identity.check(actor);
    return HttpSupport.ok(service.list(actor, unreadOnly, cursor, Cursor.size(pageSize)));
  }

  @PutMapping("/notifications/{id}/read")
  public ResponseEntity<Row> read(HttpServletRequest r, @PathVariable UUID id) {
    UUID actor = HttpSupport.actor(r);
    identity.check(actor);
    return HttpSupport.ok(service.read(actor, id));
  }

  @GetMapping("/admin/delivery")
  public ResponseEntity<Row> health(HttpServletRequest r) {
    identity.requireAdmin(HttpSupport.actor(r));
    return HttpSupport.ok(service.health());
  }

  @GetMapping("/notifications/preferences")
  public ResponseEntity<Row> preferences(HttpServletRequest r) {
    UUID actor = HttpSupport.actor(r);
    identity.check(actor);
    return HttpSupport.ok(service.preferences(actor));
  }

  @PutMapping("/notifications/preferences")
  public ResponseEntity<Row> updatePreferences(
      HttpServletRequest r, @RequestBody Map<String, Object> b) {
    UUID actor = HttpSupport.actor(r);
    identity.check(actor);
    return HttpSupport.ok(service.updatePreferences(actor, b));
  }
}
