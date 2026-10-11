package edu.campusbuddy.activity.api;

import edu.campusbuddy.activity.application.*;
import edu.campusbuddy.shared.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {
  private final ActivityService service;

  public ActivityController(ActivityService s) {
    service = s;
  }

  @GetMapping
  public ResponseEntity<Row> list(@RequestParam Map<String, Object> query) {
    return HttpSupport.ok(service.search(query, false));
  }

  @PostMapping("/search")
  public ResponseEntity<Row> search(@RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.search(b, true));
  }

  @GetMapping("/{id}")
  public ResponseEntity<Row> detail(HttpServletRequest r, @PathVariable UUID id) {
    return HttpSupport.ok(service.details(HttpSupport.optional(r), id));
  }

  @PostMapping
  public ResponseEntity<Row> publish(HttpServletRequest r, @RequestBody Map<String, Object> b) {
    return HttpSupport.created(service.publish(HttpSupport.actor(r), b, HttpSupport.key(r)));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<Row> change(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.change(HttpSupport.actor(r), id, b));
  }

  @PostMapping("/{id}/cancellation")
  public ResponseEntity<Row> cancel(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.cancel(HttpSupport.actor(r), id, b));
  }

  @PostMapping("/{id}/completion")
  public ResponseEntity<Row> complete(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.complete(HttpSupport.actor(r), id, b));
  }

  @PostMapping("/{id}/archive")
  public ResponseEntity<Row> archive(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.archive(HttpSupport.actor(r), id, b));
  }
}
