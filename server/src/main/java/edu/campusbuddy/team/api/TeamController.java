package edu.campusbuddy.team.api;

import edu.campusbuddy.shared.*;
import edu.campusbuddy.team.application.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class TeamController {
  private final TeamService service;

  public TeamController(TeamService s) {
    service = s;
  }

  @PostMapping("/activities/{id}/applications")
  public ResponseEntity<Row> apply(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.created(service.apply(HttpSupport.actor(r), id, b, HttpSupport.key(r)));
  }

  @GetMapping("/activities/{id}/applications")
  public ResponseEntity<Row> applications(HttpServletRequest r, @PathVariable UUID id) {
    return HttpSupport.ok(service.applications(HttpSupport.actor(r), id));
  }

  @PostMapping("/activities/{id}/applications/{aid}/approval")
  public ResponseEntity<Row> approve(
      HttpServletRequest r,
      @PathVariable UUID id,
      @PathVariable UUID aid,
      @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.approve(HttpSupport.actor(r), id, aid, b, HttpSupport.key(r)));
  }

  @PostMapping("/activities/{id}/applications/{aid}/withdrawal")
  public ResponseEntity<Row> withdraw(
      HttpServletRequest r,
      @PathVariable UUID id,
      @PathVariable UUID aid,
      @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.resolve(HttpSupport.actor(r), id, aid, b, true));
  }

  @PostMapping("/activities/{id}/applications/{aid}/rejection")
  public ResponseEntity<Row> reject(
      HttpServletRequest r,
      @PathVariable UUID id,
      @PathVariable UUID aid,
      @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.resolve(HttpSupport.actor(r), id, aid, b, false));
  }

  @PostMapping("/activities/{id}/participations/me/exit")
  public ResponseEntity<Row> exit(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.exit(HttpSupport.actor(r), id, b, HttpSupport.key(r)));
  }

  @PatchMapping("/activities/{id}/participations/me/confirmation")
  public ResponseEntity<Row> confirm(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.confirm(HttpSupport.actor(r), id, b));
  }

  @PostMapping("/activities/{id}/participations/me/checkin")
  public ResponseEntity<Row> checkin(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.checkin(HttpSupport.actor(r), id, b));
  }

  @GetMapping("/activities/{id}/participations")
  public ResponseEntity<Row> members(HttpServletRequest r, @PathVariable UUID id) {
    return HttpSupport.ok(service.members(HttpSupport.actor(r), id));
  }

  @GetMapping("/groups/{id}/members")
  public ResponseEntity<Row> groupMembers(HttpServletRequest r, @PathVariable UUID id) {
    UUID actor = HttpSupport.actor(r);
    return HttpSupport.ok(service.members(actor, service.grant(actor, id, false).id("activityId")));
  }

  @PostMapping("/activities/{id}/participations/{uid}/removal")
  public ResponseEntity<Row> remove(
      HttpServletRequest r,
      @PathVariable UUID id,
      @PathVariable UUID uid,
      @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(
        service.remove(HttpSupport.actor(r), id, uid, Input.text(b, "reason", 500, true), false));
  }

  @GetMapping("/me/participations")
  public ResponseEntity<Row> mine(HttpServletRequest r) {
    return HttpSupport.ok(service.mine(HttpSupport.actor(r)));
  }
}
