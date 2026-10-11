package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.activity.application.ActivityService;
import edu.campusbuddy.chat.application.ChatService;
import edu.campusbuddy.feedback.application.FeedbackService;
import edu.campusbuddy.governance.application.GovernanceService;
import edu.campusbuddy.identity.application.IdentityService;
import edu.campusbuddy.identity.infrastructure.IdentityRepository;
import edu.campusbuddy.notification.application.*;
import edu.campusbuddy.notification.infrastructure.MockPushAdapter;
import edu.campusbuddy.shared.*;
import edu.campusbuddy.team.application.TeamService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.ssl.enabled=false",
      "server.servlet.session.cookie.secure=false",
      "campus.scheduler-enabled=false",
      "campus.seed-enabled=false"
    })
@ActiveProfiles("demo")
@Import(PgIntegrationSupport.TimeConfiguration.class)
abstract class PgIntegrationSupport {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    String url = System.getenv("TEST_DB_URL");
    if (url == null
        || !url.matches(
            "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):5432/campus_buddy_mvp2_test(?:\\?.*)?"))
      throw new IllegalStateException(
          "TEST_DB_URL must explicitly select the isolated local campus_buddy_mvp2_test database");
    r.add("spring.datasource.url", () -> url);
    r.add(
        "spring.datasource.username", () -> Objects.requireNonNull(System.getenv("TEST_DB_USER")));
    r.add(
        "spring.datasource.password",
        () -> Objects.requireNonNull(System.getenv("TEST_DB_PASSWORD")));
  }

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    volatile Instant current = Instant.parse("2030-03-01T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return current;
    }

    void set(Instant i) {
      current = i;
    }

    void plus(long seconds) {
      current = current.plusSeconds(seconds);
    }
  }

  @Autowired ActivityService activities;
  @Autowired TeamService team;
  @Autowired ChatService chat;
  @Autowired FeedbackService feedback;
  @Autowired GovernanceService governance;
  @Autowired IdentityService identity;
  @Autowired IdentityRepository identities;
  @Autowired NotificationService notifications;
  @Autowired DeliveryWorker worker;
  @Autowired MockPushAdapter push;
  @Autowired JdbcTemplate jdbc;
  @Autowired Sql sql;
  @Autowired Json json;
  @Autowired TestClock clock;
  @LocalServerPort int port;
  static final UUID ALICE = user(1),
      BOB = user(2),
      CARA = user(3),
      DAN = user(4),
      UNVERIFIED = user(5),
      ADMIN = user(6);

  static UUID user(int n) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
  }

  @BeforeEach
  void resetDatabase() {
    assertEquals(
        "campus_buddy_mvp2_test", jdbc.queryForObject("select current_database()", String.class));
    jdbc.execute("drop trigger if exists injected_notification_failure on notification");
    jdbc.execute("drop function if exists injected_notification_failure()");
    jdbc.execute("truncate table user_account cascade");
    jdbc.execute("truncate table spring_session cascade");
    clock.set(Instant.parse("2030-03-01T00:00:00Z"));
    for (int i = 1; i <= 6; i++)
      identities.seed(user(i), "测试同学" + i, i == 6 ? "ADMIN" : "USER", i != 5);
    push.setUnavailable(false);
    push.setDelayMillis(0);
  }

  Row body(String title, int capacity, Instant start, Instant end) {
    return Row.of(
        "title",
        title,
        "type",
        "STUDY",
        "description",
        "测试活动",
        "campus",
        "主校区",
        "tags",
        List.of("自习"),
        "startAt",
        start.toString(),
        "endAt",
        end.toString(),
        "deadline",
        start.minusSeconds(1800).toString(),
        "confirmationDeadline",
        start.toString(),
        "capacity",
        capacity,
        "publicLocation",
        "图书馆",
        "exactLocation",
        "三楼东侧");
  }

  UUID activity(UUID owner, int cap, long start, long end) {
    return activities
        .publish(
            owner,
            body("测试活动", cap, clock.instant().plusSeconds(start), clock.instant().plusSeconds(end)),
            UUID.randomUUID())
        .id("id");
  }

  Row apply(UUID user, UUID a) {
    return team.apply(user, a, Row.of("note", "准时参加"), UUID.randomUUID());
  }

  Row approval(Row app) {
    return Row.of("round", app.num("round"), "expectedVersion", app.num("version"));
  }

  Row approve(UUID owner, UUID a, Row app) {
    return team.approve(owner, a, app.id("id"), approval(app), UUID.randomUUID());
  }

  Row part(UUID a, UUID u) {
    return sql.must("select * from participation where activity_id=? and user_id=?", a, u);
  }

  Problem problem(String code, Runnable action) {
    Problem p = assertThrows(Problem.class, action::run);
    assertEquals(code, p.code);
    return p;
  }

  List<Object> race(Supplier<Row> first, Supplier<Row> second) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
    try {
      List<Future<Object>> jobs = new ArrayList<>();
      for (Supplier<Row> action : List.of(first, second))
        jobs.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!go.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("concurrency barrier timed out");
                  try {
                    return action.get();
                  } catch (Problem p) {
                    return p;
                  }
                }));
      assertTrue(ready.await(5, TimeUnit.SECONDS), "both concurrent actors must be ready");
      go.countDown();
      return List.of(jobs.get(0).get(20, TimeUnit.SECONDS), jobs.get(1).get(20, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  void oneWinner(List<Object> results, String rejection) {
    assertEquals(1, results.stream().filter(Row.class::isInstance).count(), results.toString());
    List<Problem> errors =
        results.stream().filter(Problem.class::isInstance).map(Problem.class::cast).toList();
    assertEquals(1, errors.size());
    assertEquals(rejection, errors.getFirst().code);
  }
}
