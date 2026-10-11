package edu.campusbuddy.shared;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http, Json json) throws Exception {
    HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
    repository.setHeaderName("X-CSRF-Token");
    CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
    http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .csrf(c -> c.csrfTokenRepository(repository).csrfTokenRequestHandler(handler))
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable())
        .requestCache(c -> c.disable())
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'"
                                + " 'unsafe-inline'; img-src 'self' data: https:; connect-src"
                                + " 'self'; frame-ancestors 'none'; base-uri 'self'; form-action"
                                + " 'self'")))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (req, res, ex) -> {
                      res.setStatus(403);
                      res.setContentType("application/json;charset=UTF-8");
                      res.getWriter()
                          .write(
                              json.write(
                                  Row.of(
                                      "code",
                                      "CSRF_INVALID",
                                      "message",
                                      "请求校验失败，请刷新页面重试",
                                      "requestId",
                                      Trace.id())));
                    }));
    return http.build();
  }
}
