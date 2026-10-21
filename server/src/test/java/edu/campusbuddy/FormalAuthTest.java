package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.identity.application.*;
import edu.campusbuddy.shared.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.ssl.enabled=false", "server.servlet.session.cookie.secure=false",
    "campus.scheduler-enabled=false", "campus.auth.trust-loopback-proxy=true"
})
@ActiveProfiles("formal")
class FormalAuthTest {
  private static final String PASSWORD = "Test-only passphrase 47!";
  private static final String NEXT_PASSWORD = "Different test passphrase 86!";
  @LocalServerPort int port;
  @Autowired Sql sql;
  @Autowired Json json;
  @Autowired AccountAuthService auth;
  @Autowired DataSource dataSource;
  @Autowired JdbcIndexedSessionRepository sessions;
  private final List<Client> clients = new ArrayList<>();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    String url = System.getenv("TEST_AUTH_DB_URL");
    if (url == null || !url.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):5432/campus_buddy_auth_test(?:\\?.*)?"))
      throw new IllegalStateException("TEST_AUTH_DB_URL must select the isolated campus_buddy_auth_test database");
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", () -> Objects.requireNonNull(System.getenv("TEST_DB_USER")));
    registry.add("spring.datasource.password", () -> Objects.requireNonNull(System.getenv("TEST_DB_PASSWORD")));
  }

  @BeforeEach
  void reset() {
    assertEquals("campus_buddy_auth_test", sql.must("select current_database() as name").text("name"));
    sql.update("truncate table user_account cascade");
    sql.update("truncate table spring_session cascade");
    sql.update("truncate table auth_attempt_window");
  }

  @AfterEach
  void closeClients() { clients.forEach(c -> c.http.close()); }

  record Reply(int status, Row body, HttpHeaders headers) {
    Row data() { return new Row((Map<String, Object>) body.get("data")); }
    Row user() { return new Row((Map<String, Object>) data().get("user")); }
  }

  class Client {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
    String csrf;
    String clientIp = "198.51.100.10";

    Client() throws Exception { clients.add(this); refresh(); }

    void refresh() throws Exception {
      Reply session = call("GET", "/auth/session", null);
      assertEquals(200, session.status());
      assertEquals(false, session.data().get("demo"));
      csrf = session.data().text("csrf");
    }

    Reply call(String method, String pathname, Object body) throws Exception {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + pathname))
          .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
          .header("X-Forwarded-For", clientIp).header("Idempotency-Key", UUID.randomUUID().toString());
      if (csrf != null) request.header("X-CSRF-Token", csrf);
      var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
      Row envelope = json.row(response.body());
      assertTrue(response.statusCode() < 500, response.body());
      assertFalse(response.body().contains(PASSWORD), "password leaked into HTTP response");
      assertFalse(response.body().contains("passwordHash"));
      return new Reply(response.statusCode(), envelope, response.headers());
    }

    UUID register(String name) throws Exception {
      Reply response = call("POST", "/auth/register", Row.of("username", name, "password", PASSWORD, "nickname", "注册用户"));
      assertEquals(201, response.status(), response.body().toString());
      refresh();
      return response.user().id("id");
    }

    Reply login(String username, String password) throws Exception {
      Reply reply = call("POST", "/auth/login", Row.of("username", username, "password", password));
      if (reply.status() == 200) refresh();
      return reply;
    }

    String cookie() {
      return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("CAMPUS_SESSION"))
          .map(c -> c.getName() + "=" + c.getValue()).findFirst().orElseThrow();
    }
  }

  @Test
  void registrationCreatesOrdinaryUnverifiedAccountAndSaltedCredentials() throws Exception {
    Client alice = new Client();
    String anonymousCookie = alice.cookie(), anonymousToken = alice.csrf;
    UUID id = alice.register("Alice_01");
    assertNotEquals(anonymousCookie, alice.cookie());
    assertNotEquals(anonymousToken, alice.csrf);
    Row account = sql.must("select * from user_account where id=?", id);
    assertEquals("USER", account.text("role"));
    assertFalse(account.bool("demo"));
    assertFalse(account.bool("verified"));
    Row credential = sql.must("select * from account_credential where user_id=?", id);
    assertEquals("alice_01", credential.text("username"));
    assertTrue(AuthPasswords.encoder().matches(PASSWORD, credential.text("passwordHash")));
    UUID bob = new Client().register("Bob_01");
    assertNotEquals(credential.text("passwordHash"), sql.must("select password_hash from account_credential where user_id=?", bob).text("passwordHash"));
    assertEquals(403, alice.call("GET", "/admin/statistics", null).status());
    assertEquals(200, new Client().login("ALICE_01", PASSWORD).status());
  }

  @Test
  void formalModeRejectsDemoLoginAndPrivilegeInjection() throws Exception {
    Client client = new Client();
    assertEquals(0, sql.count("select count(*) from user_account"));
    assertEquals(404, client.call("GET", "/auth/demo/accounts", null).status());
    assertEquals(404, client.call("POST", "/auth/demo", Row.of("userId", UUID.randomUUID())).status());
    assertEquals(401, client.call("GET", "/me/profile", null).status());
    for (String field : List.of("role", "verified", "demo", "status")) {
      Row body = Row.of("username", "injected", "password", PASSWORD, "nickname", "普通人", field, "ADMIN");
      assertEquals(400, client.call("POST", "/auth/register", body).status());
    }
    assertEquals(0, sql.count("select count(*) from user_account"));
  }

  @Test
  void duplicateConcurrentRegistrationRollsBackEntireLosingAccount() throws Exception {
    Row body = Row.of("username", "concurrent_user", "password", PASSWORD, "nickname", "并发测试");
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      Callable<Integer> registration = () -> {
        start.await();
        try { auth.register(body); return 201; }
        catch (Problem problem) { return problem.status; }
      };
      Future<Integer> one = executor.submit(registration), two = executor.submit(registration);
      start.countDown();
      assertEquals(Set.of(201, 409), Set.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)));
    }
    assertEquals(1, sql.count("select count(*) from user_account"));
    assertEquals(1, sql.count("select count(*) from account_credential"));
    assertEquals(1, sql.count("select count(*) from privacy_setting"));
  }

  @Test
  void csrfRotationAndLogoutProtectSessions() throws Exception {
    Client client = new Client();
    String original = client.csrf;
    client.csrf = null;
    assertEquals(403, client.call("POST", "/auth/register", Row.of("username", "csrf_user", "password", PASSWORD, "nickname", "测试")).status());
    client.csrf = original;
    client.register("csrf_user");
    String current = client.csrf;
    client.csrf = original;
    assertEquals(403, client.call("PATCH", "/me/privacy", Row.of("contactVisibility", "GROUP")).status());
    client.csrf = current;
    String oldCookie = client.cookie();
    assertEquals(200, client.call("POST", "/auth/logout", Map.of()).status());
    assertEquals(401, withCookie(oldCookie, "/me/profile"));
  }

  @Test
  void passwordChangeRevokesAllSessionsAndRejectsLateSavedOldVersion() throws Exception {
    Client first = new Client();
    UUID user = first.register("password_user");
    Client second = new Client();
    assertEquals(200, second.login("password_user", PASSWORD).status());
    @SuppressWarnings("unchecked")
    SessionRepository<Session> sessionStore = (SessionRepository<Session>) (SessionRepository<?>) sessions;
    Session lateSession = sessionStore.createSession();
    lateSession.setAttribute("actor", user.toString());
    lateSession.setAttribute("authVersion", 1L);
    String firstCookie = first.cookie(), secondCookie = second.cookie();
    assertEquals(401, first.call("POST", "/auth/password", Row.of("currentPassword", "Wrong test passphrase!", "newPassword", NEXT_PASSWORD)).status());
    assertEquals(200, first.call("POST", "/auth/password", Row.of("currentPassword", PASSWORD, "newPassword", NEXT_PASSWORD)).status());
    assertEquals(0, sql.count("select count(*) from spring_session where principal_name=?", user.toString()));
    assertEquals(401, withCookie(firstCookie, "/me/profile"));
    assertEquals(401, withCookie(secondCookie, "/me/profile"));
    sessionStore.save(lateSession);
    String lateCookie = "CAMPUS_SESSION=" + Base64.getEncoder().encodeToString(lateSession.getId().getBytes(StandardCharsets.UTF_8));
    assertEquals(401, withCookie(lateCookie, "/me/profile"));
    assertEquals(401, new Client().login("password_user", PASSWORD).status());
    assertEquals(200, new Client().login("password_user", NEXT_PASSWORD).status());
  }

  @Test
  void erasureRemovesCredentialsAndOtherSessions() throws Exception {
    Client client = new Client();
    UUID user = client.register("erase_user");
    Client second = new Client();
    second.login("erase_user", PASSWORD);
    String oldCookie = second.cookie();
    assertEquals(200, client.call("DELETE", "/me/account", Row.of("confirm", true)).status());
    assertEquals(0, sql.count("select count(*) from account_credential where user_id=?", user));
    assertEquals(401, withCookie(oldCookie, "/me/profile"));
    assertEquals(401, new Client().login("erase_user", PASSWORD).status());
  }

  @Test
  void registrationLimiterCannotBeBypassedWithNewCookies() throws Exception {
    for (int i = 0; i < 10; i++)
      assertEquals(400, new Client().call("POST", "/auth/register", Map.of()).status());
    assertEquals(429, new Client().call("POST", "/auth/register", Map.of()).status());
    sql.update("update auth_attempt_window set expires_at=now()-interval '1 second'");
    assertEquals(400, new Client().call("POST", "/auth/register", Map.of()).status());
  }

  @Test
  void loginAccountLimitSurvivesChangingClientIpAndUnknownAccountMatchesWrongPassword() throws Exception {
    new Client().register("limited_user");
    Client client = new Client();
    Reply missing = client.login("nonexistent_user", PASSWORD);
    Reply wrong = client.login("limited_user", "Incorrect test password!");
    assertEquals(missing.status(), wrong.status());
    assertEquals(missing.body().text("code"), wrong.body().text("code"));
    assertEquals(missing.body().text("message"), wrong.body().text("message"));
    for (int i = 1; i < 20; i++) {
      client.clientIp = "198.51.100." + (i + 20);
      assertEquals(401, client.login("limited_user", "Incorrect test password!").status());
    }
    Client another = new Client();
    another.clientIp = "203.0.113.20";
    assertEquals(429, another.login("LIMITED_USER", PASSWORD).status());
    sql.update("update auth_attempt_window set expires_at=now()-interval '1 second'");
    assertEquals(200, another.login("limited_user", PASSWORD).status());
  }

  @Test
  void administratorIsCreatedOfflineAndMustChangeTemporaryPassword() throws Exception {
    new Client().register("first_user");
    String hash = AuthPasswords.encoder().encode(PASSWORD);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      AdminBootstrap.create(connection, "operator", hash);
      connection.commit();
      assertThrows(IllegalStateException.class, () -> AdminBootstrap.create(connection, "another_admin", hash));
      connection.rollback();
    }
    Client admin = new Client();
    assertTrue(admin.login("operator", PASSWORD).user().bool("passwordChangeRequired"));
    Reply blocked = admin.call("GET", "/admin/statistics", null);
    assertEquals(403, blocked.status());
    assertEquals("PASSWORD_CHANGE_REQUIRED", blocked.body().text("code"));
    assertEquals(200, admin.call("POST", "/auth/password", Row.of("currentPassword", PASSWORD, "newPassword", NEXT_PASSWORD)).status());
    admin.refresh();
    assertEquals(200, admin.login("operator", NEXT_PASSWORD).status());
    assertEquals(200, admin.call("GET", "/admin/statistics", null).status());
    assertEquals(1, sql.count("select count(*) from user_account where role='ADMIN'"));
  }

  @Test
  void registeredUnverifiedUsersCanPublishApplyApproveAndChat() throws Exception {
    Client owner = new Client(), member = new Client();
    owner.register("activity_owner");
    member.register("activity_member");
    Instant start = Instant.now().plusSeconds(86400);
    Row activity = Row.of("title", "正式账号活动", "type", "STUDY", "description", "测试", "campus", "主校区",
        "tags", List.of("自习"), "startAt", start.toString(), "endAt", start.plusSeconds(3600).toString(),
        "deadline", start.minusSeconds(1800).toString(), "confirmationDeadline", start.toString(),
        "capacity", 3, "publicLocation", "图书馆", "exactLocation", "三楼");
    Reply published = owner.call("POST", "/activities", activity);
    assertEquals(201, published.status(), published.body().toString());
    UUID id = published.data().id("id");
    Reply application = member.call("POST", "/activities/" + id + "/applications", Row.of("note", "参加"));
    assertEquals(201, application.status());
    Row stored = sql.must("select id,round,version from application where activity_id=?", id);
    Reply approval = owner.call("POST", "/activities/" + id + "/applications/" + stored.id("id") + "/approval",
        Row.of("round", stored.num("round"), "expectedVersion", stored.num("version")));
    assertEquals(200, approval.status(), approval.body().toString());
    UUID group = approval.data().id("groupId");
    assertEquals(201, member.call("POST", "/groups/" + group + "/messages", Row.of("clientMessageId", UUID.randomUUID(), "type", "TEXT", "body", "正式账号可参与")).status());
    assertEquals(200, owner.call("GET", "/groups/" + group + "/messages", null).status());
    assertEquals(0, sql.count("select count(*) from user_account where verified=true"));
    assertTrue(sql.count("select count(*) from notification") > 0, "in-app notifications remain available");
    assertEquals(0, sql.count("select count(*) from delivery_task"), "formal mode must not queue unconfigured external deliveries");
  }

  @Test
  void disabledUserCanSignInToAppealButCannotPublish() throws Exception {
    Client client = new Client();
    UUID user = client.register("disabled_user");
    sql.update("update user_account set status='DISABLED' where id=?", user);
    Client another = new Client();
    assertEquals(200, another.login("disabled_user", PASSWORD).status());
    assertEquals(200, another.call("GET", "/reports", null).status());
    assertEquals(403, another.call("POST", "/activities", Map.of()).status());
  }

  private int withCookie(String cookie, String path) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
          .header("Cookie", cookie).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
  }
}
