package edu.campusbuddy.chat.api;

import edu.campusbuddy.chat.application.*;
import edu.campusbuddy.shared.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/groups/{id}")
public class ChatController {
  private final ChatService service;

  public ChatController(ChatService s) {
    service = s;
  }

  @GetMapping("/messages")
  public ResponseEntity<Row> messages(
      HttpServletRequest r,
      @PathVariable UUID id,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer pageSize) {
    return HttpSupport.ok(
        service.messages(HttpSupport.actor(r), id, cursor, Cursor.size(pageSize)));
  }

  @PostMapping("/messages")
  public ResponseEntity<Row> send(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.created(service.send(HttpSupport.actor(r), id, b));
  }

  @PutMapping("/pin")
  public ResponseEntity<Row> pin(
      HttpServletRequest r, @PathVariable UUID id, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(service.pin(HttpSupport.actor(r), id, b));
  }
}
