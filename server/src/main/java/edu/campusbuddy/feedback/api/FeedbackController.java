package edu.campusbuddy.feedback.api;

import edu.campusbuddy.feedback.application.*;
import edu.campusbuddy.shared.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/activities/{id}/feedback")
public class FeedbackController {
  private final FeedbackService service;

  public FeedbackController(FeedbackService s) {
    service = s;
  }

  @GetMapping
  public ResponseEntity<Row> eligible(HttpServletRequest r, @PathVariable UUID id) {
    return HttpSupport.ok(service.eligible(HttpSupport.actor(r), id));
  }

  @PostMapping
  public ResponseEntity<Row> submit(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.created(service.submit(HttpSupport.actor(r), id, b));
  }
}
