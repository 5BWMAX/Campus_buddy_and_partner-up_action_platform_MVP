package edu.campusbuddy.identity.api;

import edu.campusbuddy.shared.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("formal")
@Order(-90)
public class FormalSessionFilter extends OncePerRequestFilter {
  private final Sql sql;
  private final Json json;
  private static final Set<String> PASSWORD_ACTIONS = Set.of(
      "/api/v1/auth/session", "/api/v1/auth/password", "/api/v1/auth/logout");

  public FormalSessionFilter(Sql sql, Json json) {
    this.sql = sql;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (req.getRequestURI().startsWith("/api/")) {
      HttpSession session = req.getSession(false);
      if (session != null && session.getAttribute("actor") != null) {
        UUID actor;
        try { actor = UUID.fromString(session.getAttribute("actor").toString()); }
        catch (IllegalArgumentException e) { actor = null; }
        Row state = actor == null ? null : sql.one("select c.session_version,c.password_change_required,u.demo,u.status from account_credential c join user_account u on u.id=c.user_id where c.user_id=?", actor);
        Object version = session.getAttribute("authVersion");
        if (state == null || state.bool("demo") || state.text("status").equals("DELETED")
            || !(version instanceof Number) || ((Number) version).longValue() != ((Number) state.get("sessionVersion")).longValue()) {
          session.invalidate();
          reject(res, 401, "AUTH_REQUIRED", "登录已失效，请重新登录");
          return;
        }
        if (state.bool("passwordChangeRequired") && !PASSWORD_ACTIONS.contains(req.getRequestURI())) {
          reject(res, 403, "PASSWORD_CHANGE_REQUIRED", "请先修改临时密码");
          return;
        }
      }
    }
    chain.doFilter(req, res);
  }

  private void reject(HttpServletResponse res, int status, String code, String message) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(json.write(Row.of("code", code, "message", message, "requestId", Trace.id())));
  }
}
