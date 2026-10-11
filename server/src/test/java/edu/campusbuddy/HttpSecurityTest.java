package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class HttpSecurityTest extends PgIntegrationSupport {
  record Reply(int status, Row body, HttpHeaders headers) {
    Row data() {
      return new Row((Map<String, Object>) body.get("data"));
    }
  }

  class Client {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    String csrf;

    Client() throws Exception {
      Reply r = call("GET", "/auth/session", null, false, null);
      assertEquals(200, r.status);
      csrf = r.data().text("csrf");
      assertFalse(csrf.isBlank());
    }

    Reply call(String method, String path, Object body, boolean token, UUID key) throws Exception {
      var b =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json");
      if (token) b.header("X-CSRF-Token", csrf);
      if (key != null) b.header("Idempotency-Key", key.toString());
      var response =
          http.send(
              b.method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofString(json.write(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      Row out = json.row(response.body());
      assertEquals(
          response.headers().firstValue("X-Request-ID").orElseThrow(), out.text("requestId"));
      assertFalse(out.text("requestId").isBlank());
      assertTrue(response.statusCode() < 500, response.body());
      return new Reply(response.statusCode(), out, response.headers());
    }

    void login(UUID id) throws Exception {
      assertEquals(200, call("POST", "/auth/demo", Row.of("userId", id), true, null).status);
    }
  }

  @Test
  void csrfSessionPublishLogoutAndUnverifiedAccountAreEnforced() throws Exception {
    Client c = new Client();
    c.login(ALICE);
    assertEquals(
        1,
        sql.count("select count(*) from spring_session where principal_name=?", ALICE.toString()));
    Row b =
        body("HTTP发布验证", 2, clock.instant().plusSeconds(7200), clock.instant().plusSeconds(10800));
    UUID key = UUID.randomUUID();
    Reply denied = c.call("POST", "/activities", b, false, key);
    assertEquals(403, denied.status);
    assertEquals("CSRF_INVALID", denied.body.text("code"));
    assertEquals(0, sql.count("select count(*) from activity"));
    Reply ok = c.call("POST", "/activities", b, true, key);
    assertEquals(201, ok.status);
    UUID id = ok.data().id("id");
    assertEquals(1, sql.count("select count(*) from activity where id=?", id));
    assertEquals(1, team.occupied(id));
    assertEquals(200, c.call("POST", "/auth/logout", null, true, null).status);
    assertEquals(
        0,
        sql.count("select count(*) from spring_session where principal_name=?", ALICE.toString()));
    assertEquals(401, c.call("GET", "/me/profile", null, false, null).status);
    Client u = new Client();
    u.login(UNVERIFIED);
    assertEquals(403, u.call("POST", "/activities", b, true, UUID.randomUUID()).status);
    assertEquals(1, sql.count("select count(*) from activity"));
  }

  @Test
  void groupReadsAndWritesUse404ForNonMembersAndExitedMembers() throws Exception {
    UUID a = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, a, apply(BOB, a));
    UUID group = team.group(a).id("id");
    Client c = new Client();
    c.login(CARA);
    assertEquals(404, c.call("GET", "/groups/" + group + "/messages", null, false, null).status);
    assertEquals(
        404,
        c.call(
                "POST",
                "/groups/" + group + "/messages",
                Row.of("clientMessageId", UUID.randomUUID(), "body", "越权内容"),
                true,
                null)
            .status);
    assertEquals(403, c.call("GET", "/admin/statistics", null, false, null).status);
    c.login(BOB);
    assertEquals(200, c.call("GET", "/groups/" + group + "/messages", null, false, null).status);
    assertEquals(
        200,
        c.call(
                "POST",
                "/activities/" + a + "/participations/me/exit",
                Row.of("reason", "临时有事"),
                true,
                UUID.randomUUID())
            .status);
    assertEquals(404, c.call("GET", "/groups/" + group + "/messages", null, false, null).status);
    assertEquals(0, sql.count("select count(*) from message"));
  }

  @Test
  void bodyLimitChecksChunkedBodyAndMissingRoutesHave404() throws Exception {
    Client c = new Client();
    c.login(ALICE);
    byte[] bytes =
        json.write(Row.of("nickname", "x".repeat(70000)))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me/profile"))
            .header("Content-Type", "application/json")
            .header("X-CSRF-Token", c.csrf)
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(bytes)))
            .build();
    var out = c.http.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(413, out.statusCode());
    assertEquals("PAYLOAD_TOO_LARGE", json.row(out.body()).text("code"));
    assertEquals("测试同学1", identity.profile(ALICE).text("nickname"));
    assertEquals(404, c.call("GET", "/does-not-exist", null, false, null).status);
  }

  @Test
  void deletingAccountRevokesOtherSessionsAndScrubsStoredPrivateData() throws Exception {
    Client first = new Client(), second = new Client();
    first.login(ALICE);
    second.login(ALICE);
    assertEquals(
        200,
        first.call("PATCH", "/me/profile", Row.of("contact", "private-138-test"), true, null)
            .status);
    UUID a = activity(ALICE, 2, 7200, 10800);
    activities.change(ALICE, a, Row.of("expectedVersion", 0, "exactLocation", "秘密房间"));
    assertEquals(
        2,
        sql.count("select count(*) from spring_session where principal_name=?", ALICE.toString()));
    assertEquals(
        200, first.call("DELETE", "/me/account", Row.of("confirm", true), true, null).status);
    assertEquals(401, second.call("GET", "/me/profile", null, false, null).status);
    assertEquals(
        0,
        sql.count("select count(*) from spring_session where principal_name=?", ALICE.toString()));
    Row stored = sql.must("select * from user_account where id=?", ALICE);
    assertNull(stored.get("contactCiphertext"));
    assertNull(stored.get("campusSubject"));
    assertEquals("DELETED", stored.text("status"));
    assertNull(activities.find(a).get("exactLocationCiphertext"));
    assertEquals(
        0,
        sql.count(
            "select count(*) from activity_change where"
                + " jsonb_exists(old_values,'exactLocationCiphertext') or"
                + " jsonb_exists(new_values,'exactLocationCiphertext')"));
    assertEquals(1, sql.count("select count(*) from erasure_log"));
  }
}
