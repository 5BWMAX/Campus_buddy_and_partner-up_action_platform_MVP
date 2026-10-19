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
  public ResponseEntity<Row> list(HttpServletRequest r, @RequestParam Map<String, Object> query) {
    return HttpSupport.ok(service.search(HttpSupport.optional(r), query, false));
  }

  @PostMapping("/search")
  public ResponseEntity<Row> search(HttpServletRequest r, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.search(HttpSupport.optional(r), b, true));
  }

  @GetMapping("/search-history")
  public ResponseEntity<Row> searchHistory(HttpServletRequest r) {
    return HttpSupport.ok(service.searchHistory(HttpSupport.actor(r)));
  }

  @DeleteMapping("/search-history")
  public ResponseEntity<Row> clearSearchHistory(HttpServletRequest r) {
    service.clearSearchHistory(HttpSupport.actor(r));
    return HttpSupport.ok(Row.of("cleared", true));
  }

  @DeleteMapping("/search-history/{keyword}")
  public ResponseEntity<Row> deleteSearchHistory(
      HttpServletRequest r, @PathVariable String keyword) {
    service.deleteSearchHistory(HttpSupport.actor(r), keyword);
    return HttpSupport.ok(Row.of("keyword", keyword, "deleted", true));
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

  // ===== 活动收藏 =====
  @PostMapping("/{id}/favorite")
  public ResponseEntity<Row> favorite(HttpServletRequest r, @PathVariable UUID id) {
    return HttpSupport.ok(service.favorite(HttpSupport.actor(r), id));
  }

  @DeleteMapping("/{id}/favorite")
  public ResponseEntity<Row> unfavorite(HttpServletRequest r, @PathVariable UUID id) {
    return HttpSupport.ok(service.unfavorite(HttpSupport.actor(r), id));
  }

  @GetMapping("/favorites/mine")
  public ResponseEntity<Row> favorites(HttpServletRequest r, @RequestParam Map<String, Object> query) {
    return HttpSupport.ok(
        service.favorites(
            HttpSupport.actor(r),
            (String) query.getOrDefault("cursor", ""),
            Cursor.size(query.get("pageSize"))));
  }
}
