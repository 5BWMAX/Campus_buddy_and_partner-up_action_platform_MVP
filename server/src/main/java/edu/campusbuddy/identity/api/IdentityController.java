package edu.campusbuddy.identity.api;

import edu.campusbuddy.activity.api.*;
import edu.campusbuddy.identity.application.*;
import edu.campusbuddy.shared.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
  private final IdentityService identity;
  private final ObjectProvider<IdentityAdapter> adapters;
  private final ActivityApi activities;

  public IdentityController(IdentityService i, ObjectProvider<IdentityAdapter> a, ActivityApi act) {
    identity = i;
    adapters = a;
    activities = act;
  }

  @GetMapping("/auth/session")
  public ResponseEntity<Row> session(HttpServletRequest req) {
    CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
    UUID actor = HttpSupport.optional(req);
    return HttpSupport.ok(
        Row.of(
            "user",
            actor == null ? null : identity.profile(actor),
            "csrf",
            token.getToken(),
            "demo",
            adapters.getIfAvailable() != null));
  }

  @GetMapping("/auth/demo/accounts")
  public ResponseEntity<Row> accounts() {
    IdentityAdapter a = adapters.getIfAvailable();
    if (a == null) throw Problem.missing();
    return HttpSupport.ok(a.accounts());
  }

  @PostMapping("/auth/demo")
  public ResponseEntity<Row> login(HttpServletRequest req, @RequestBody Map<String, Object> b) {
    IdentityAdapter a = adapters.getIfAvailable();
    if (a == null) throw Problem.missing();
    Row u = a.authenticate(Input.uuid(b.get("userId")));
    req.getSession();
    req.changeSessionId();
    req.getSession().setAttribute("actor", u.id("id").toString());
    req.getSession()
        .setAttribute(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, u.id("id").toString());
    return HttpSupport.ok(Row.of("user", identity.profile(u.id("id"))));
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<Row> logout(HttpServletRequest req) {
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    return HttpSupport.ok(Row.of("loggedOut", true));
  }

  @GetMapping("/me/profile")
  public ResponseEntity<Row> profile(HttpServletRequest r) {
    return HttpSupport.ok(identity.profile(HttpSupport.actor(r)));
  }

  @PatchMapping("/me/profile")
  public ResponseEntity<Row> profile(HttpServletRequest r, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(identity.update(HttpSupport.actor(r), b));
  }

  @PatchMapping("/me/privacy")
  public ResponseEntity<Row> privacy(HttpServletRequest r, @RequestBody Map<String, Object> b) {
    return HttpSupport.ok(identity.privacy(HttpSupport.actor(r), b));
  }

  @DeleteMapping("/me/account")
  public ResponseEntity<Row> erase(HttpServletRequest r, @RequestBody Map<String, Object> b) {
    UUID actor = HttpSupport.actor(r);
    Problem.require(Boolean.TRUE.equals(b.get("confirm")), 400, "VALIDATION_FAILED", "请明确确认注销");
    Row out = identity.erase(actor);
    r.getSession().invalidate();
    return HttpSupport.ok(out);
  }
}
