package edu.campusbuddy.identity.api;

import edu.campusbuddy.identity.application.*;
import edu.campusbuddy.shared.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("formal")
@RequestMapping("/api/v1/auth")
public class AccountAuthController {
  private final AccountAuthService auth;
  private final IdentityService identity;
  private final AuthAttemptLimiter limiter;
  private final AuthClientAddress clients;

  public AccountAuthController(AccountAuthService auth, IdentityService identity,
      AuthAttemptLimiter limiter, AuthClientAddress clients) {
    this.auth = auth;
    this.identity = identity;
    this.limiter = limiter;
    this.clients = clients;
  }

  @PostMapping("/register")
  public ResponseEntity<Row> register(HttpServletRequest request, @RequestBody Map<String, Object> body) {
    limiter.consume("register", clients.client(request), null);
    AccountAuthService.Login login = auth.register(body);
    establish(request, login);
    return HttpSupport.created(Row.of("user", identity.profile(login.userId())));
  }

  @PostMapping("/login")
  public ResponseEntity<Row> login(HttpServletRequest request, @RequestBody Map<String, Object> body) {
    String account = body.get("username") instanceof String name ? name.toLowerCase(Locale.ROOT) : "";
    limiter.consume("login", clients.client(request), account);
    AccountAuthService.Login login = auth.login(body);
    establish(request, login);
    return HttpSupport.ok(Row.of("user", identity.profile(login.userId())));
  }

  @PostMapping("/password")
  public ResponseEntity<Row> password(HttpServletRequest request, @RequestBody Map<String, Object> body) {
    UUID actor = HttpSupport.actor(request);
    limiter.consume("password", clients.client(request), actor.toString());
    auth.changePassword(actor, body);
    request.getSession().invalidate();
    return HttpSupport.ok(Row.of("loggedOut", true));
  }

  private void establish(HttpServletRequest request, AccountAuthService.Login login) {
    // Drop the anonymous/previous user's session, including its CSRF token.
    if (request.getSession(false) != null) request.getSession(false).invalidate();
    HttpSession session = request.getSession(true);
    session.setAttribute("actor", login.userId().toString());
    session.setAttribute("authVersion", login.version());
    session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, login.userId().toString());
  }
}
