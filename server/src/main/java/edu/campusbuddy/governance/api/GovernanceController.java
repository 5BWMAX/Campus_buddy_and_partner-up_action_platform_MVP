package edu.campusbuddy.governance.api;

import edu.campusbuddy.governance.application.*;
import edu.campusbuddy.shared.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GovernanceController {
  private final GovernanceService service;

  public GovernanceController(GovernanceService s) {
    service = s;
  }

  @PostMapping("/reports")
  public ResponseEntity<Row> report(HttpServletRequest r, @RequestBody Map<String, Object> b) {
    return HttpSupport.created(service.report(HttpSupport.actor(r), b));
  }

  @GetMapping("/reports")
  public ResponseEntity<Row> mine(HttpServletRequest r) {
    return HttpSupport.ok(service.list(HttpSupport.actor(r), false));
  }

  @GetMapping("/admin/reports")
  public ResponseEntity<Row> reports(HttpServletRequest r) {
    return HttpSupport.ok(service.list(HttpSupport.actor(r), true));
  }

  @PostMapping("/admin/reports/{id}/acceptance")
  public ResponseEntity<Row> accept(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.phase(HttpSupport.actor(r), id, b, true));
  }

  @PostMapping("/admin/reports/{id}/processing")
  public ResponseEntity<Row> process(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.phase(HttpSupport.actor(r), id, b, false));
  }

  @PostMapping("/admin/reports/{id}/decision")
  public ResponseEntity<Row> decide(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    b = new Row(b);
    b.put("dispositionId", HttpSupport.key(r));
    return HttpSupport.ok(service.decide(HttpSupport.actor(r), id, b));
  }

  @PostMapping("/reports/{id}/appeals")
  public ResponseEntity<Row> appeal(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.created(service.appeal(HttpSupport.actor(r), id, b));
  }

  @PostMapping("/admin/appeals/{id}/decision")
  public ResponseEntity<Row> review(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.review(HttpSupport.actor(r), id, b));
  }

  @GetMapping("/admin/statistics")
  public ResponseEntity<Row> stats(HttpServletRequest r, @RequestParam Map<String, Object> b) {
    return HttpSupport.ok(service.statistics(HttpSupport.actor(r), b));
  }

  @GetMapping("/admin/statistics/export")
  public ResponseEntity<String> export(HttpServletRequest r, @RequestParam Map<String, Object> b) {
    Row stats = service.statistics(HttpSupport.actor(r), b);
    StringBuilder csv = new StringBuilder("metric,value\r\n");
    stats.forEach((k, v) -> csv.append(k).append(',').append(v == null ? "" : v).append("\r\n"));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header("Content-Disposition", "attachment; filename=campus-statistics.csv")
        .body(csv.toString());
  }

  @GetMapping("/admin/audit")
  public ResponseEntity<Row> audit(HttpServletRequest r) {
    return HttpSupport.ok(service.audit(HttpSupport.actor(r)));
  }
}
