package edu.campusbuddy;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(
    properties = "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '1s'")
class DatabaseLockTimeoutHttpTest extends PgIntegrationSupport {
  private static final int FUTURE_TIMEOUT_SECONDS = 12;

  @Autowired PlatformTransactionManager transactions;

  record Reply(int status, Row body, HttpHeaders headers) {}

  class Client {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    String csrf;

    Client() throws Exception {
      Reply response = call("GET", "/auth/session", null, false, null);
      assertEquals(200, response.status());
      csrf = new Row((Map<String, Object>) response.body().get("data")).text("csrf");
      assertFalse(csrf.isBlank());
    }

    Reply call(String method, String path, Object body, boolean token, UUID key) throws Exception {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
              .timeout(Duration.ofSeconds(8))
              .header("Content-Type", "application/json");
      if (token) request.header("X-CSRF-Token", csrf);
      if (key != null) request.header("Idempotency-Key", key.toString());
      HttpResponse<String> response =
          http.send(
              request
                  .method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofString(json.write(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      Row output = json.row(response.body());
      assertEquals(
          response.headers().firstValue("X-Request-ID").orElseThrow(),
          output.text("requestId"));
      assertFalse(output.text("requestId").isBlank());
      return new Reply(response.statusCode(), output, response.headers());
    }

    void login(UUID id) throws Exception {
      assertEquals(200, call("POST", "/auth/demo", Row.of("userId", id), true, null).status());
    }
  }

  @Test
  void lockTimeoutUsesRealHttpExceptionMapping() throws Exception {
    assertEquals("1s", jdbc.queryForObject("show lock_timeout", String.class));
    UUID activity = activity(ALICE, 3, 7200, 10800);
    approve(ALICE, activity, apply(BOB, activity));
    Client client = new Client();
    client.login(BOB);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    CountDownLatch readerHolding = new CountDownLatch(1),
        releaseReader = new CountDownLatch(1);
    AtomicInteger readerPid = new AtomicInteger();
    try {
      Future<List<Row>> reader =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        readerPid.set(backendPid());
                        List<Row> rows = team.members(ALICE, activity);
                        readerHolding.countDown();
                        await(releaseReader, "release HTTP lock-timeout reader");
                        return rows;
                      }));
      await(readerHolding, "members reader to hold the activity shared lock");

      Reply response =
          client.call(
              "POST",
              "/activities/" + activity + "/participations/me/exit",
              Row.of("reason", "HTTP lock-timeout regression"),
              true,
              UUID.randomUUID());
      assertEquals(503, response.status());
      assertEquals("TEMPORARILY_UNAVAILABLE", response.body().text("code"));

      releaseReader.countDown();
      assertEquals(2, reader.get(FUTURE_TIMEOUT_SECONDS, SECONDS).size());
      assertEquals("ACTIVE", part(activity, BOB).text("status"));
      System.out.printf(
          Locale.ROOT,
          "HTTP lock timeout mapped to 503 while members readerPid=%d held FOR SHARE%n",
          readerPid.get());
    } finally {
      releaseReader.countDown();
      closePool(pool);
    }
  }

  private <T> T transaction(Supplier<T> action) {
    return new TransactionTemplate(transactions).execute(status -> action.get());
  }

  private int backendPid() {
    return Objects.requireNonNull(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
  }

  private void await(CountDownLatch latch, String description) {
    try {
      assertTrue(latch.await(FUTURE_TIMEOUT_SECONDS, SECONDS), "timed out waiting to " + description);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting to " + description, error);
    }
  }

  private void closePool(ExecutorService pool) {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(FUTURE_TIMEOUT_SECONDS, SECONDS)) {
        pool.shutdownNow();
        assertTrue(
            pool.awaitTermination(FUTURE_TIMEOUT_SECONDS, SECONDS),
            "HTTP lock-timeout worker pool did not terminate");
      }
    } catch (InterruptedException error) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while closing HTTP lock-timeout worker pool", error);
    }
  }
}
