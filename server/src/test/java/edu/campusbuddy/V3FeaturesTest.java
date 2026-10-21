package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.shared.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "campus.upload-dir=target/test-uploads")
class V3FeaturesTest extends PgIntegrationSupport {
  private static final byte[] PIXEL_PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=");

  record Reply(int status, Row body) {
    Row data() {
      return new Row((Map<String, Object>) body.get("data"));
    }

    String code() {
      return body.text("code");
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
      Reply r = call("GET", "/auth/session", null, false);
      assertEquals(200, r.status);
      csrf = r.data().text("csrf");
      assertFalse(csrf.isBlank());
    }

    void login(UUID id) throws Exception {
      assertEquals(200, call("POST", "/auth/demo", Row.of("userId", id), true).status);
      csrf = call("GET", "/auth/session", null, false).data().text("csrf");
    }

    Reply call(String method, String path, Object body, boolean token) throws Exception {
      boolean get = method.equals("GET");
      byte[] bytes = body == null ? new byte[0] : json.write(body).getBytes(StandardCharsets.UTF_8);
      var builder =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
              .timeout(Duration.ofSeconds(10));
      if (!get) builder.header("Content-Type", "application/json");
      if (token) builder.header("X-CSRF-Token", csrf);
      builder.method(
          method,
          get || bytes.length == 0
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(bytes));
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      Row out = json.row(response.body());
      assertEquals(response.headers().firstValue("X-Request-ID").orElseThrow(), out.text("requestId"));
      return new Reply(response.statusCode(), out);
    }

    Reply upload(byte[] bytes, String filename, String contentType) throws Exception {
      String boundary = "----campus-" + UUID.randomUUID();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(
          ("--" + boundary + "\r\n"
                  + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                  + "Content-Type: " + contentType + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.write(bytes);
      out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port + "/api/v1/media/upload"))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .header("X-CSRF-Token", csrf)
              .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      Row data = json.row(response.body());
      return new Reply(response.statusCode(), data);
    }
  }

  @Test
  void imageUploadStoresMetadataAndOnlyOwnerCanReferenceIt() throws Exception {
    Client c = new Client();
    c.login(ALICE);
    Reply uploaded = c.upload(PIXEL_PNG, "pixel.png", "image/png");
    assertEquals(200, uploaded.status, uploaded.body.toString());
    String url = uploaded.data().text("url"), fileName = uploaded.data().text("fileName");
    assertTrue(url.matches("^/uploads/[0-9a-f]{32}\\.png$"), url);
    assertEquals(1, sql.count("select count(*) from media_file where uploader_id=? and storage_path=?", ALICE, fileName));
    assertEquals("image/png", sql.must("select content_type from media_file where storage_path=?", fileName).text("contentType"));

    UUID a = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, a, apply(BOB, a)).id("groupId");
    Row sent =
        chat.send(
            ALICE,
            group,
            Row.of(
                "clientMessageId", UUID.randomUUID(), "mediaType", "IMAGE", "mediaUrl", url));
    Row message =
        ((List<?>) chat.messages(BOB, group, null, 20).get("items"))
            .stream()
            .map(x -> new Row((Map<String, Object>) x))
            .filter(x -> x.id("id").equals(sent.id("id")))
            .findFirst()
            .orElseThrow();
    assertNull(message.get("body"));
    assertEquals("IMAGE", message.text("mediaType"));
    assertEquals(url, message.text("mediaUrl"));

    problem(
        "INVALID_MEDIA",
        () ->
            chat.send(
                BOB,
                group,
                Row.of(
                    "clientMessageId", UUID.randomUUID(), "mediaType", "IMAGE", "mediaUrl", url)));
    problem(
        "INVALID_MEDIA",
        () ->
            chat.send(
                ALICE,
                group,
                Row.of(
                    "clientMessageId",
                    UUID.randomUUID(),
                    "mediaType",
                    "IMAGE",
                    "mediaUrl",
                    "https://example.com/tracker.png")));

    Reply bad = c.upload("not an image".getBytes(StandardCharsets.UTF_8), "bad.png", "image/png");
    assertEquals(400, bad.status);
    assertEquals("INVALID_IMAGE", bad.code());
    assertEquals(1, sql.count("select count(*) from media_file where uploader_id=?", ALICE));
  }

  @Test
  void searchHistoryDeleteAndClearReturnJsonAndRemoveRows() throws Exception {
    Client c = new Client();
    c.login(ALICE);
    activities.search(ALICE, Row.of("q", "library"), false);
    assertEquals(1, sql.count("select count(*) from search_history where user_id=?", ALICE));

    Reply deleted = c.call("DELETE", "/activities/search-history/library", null, true);
    assertEquals(200, deleted.status);
    assertTrue(deleted.data().bool("deleted"));
    assertEquals(0, sql.count("select count(*) from search_history where user_id=?", ALICE));

    activities.search(ALICE, Row.of("q", "second"), false);
    activities.search(ALICE, Row.of("q", "third"), false);
    assertEquals(2, sql.count("select count(*) from search_history where user_id=?", ALICE));
    Reply cleared = c.call("DELETE", "/activities/search-history", null, true);
    assertEquals(200, cleared.status);
    assertTrue(cleared.data().bool("cleared"));
    assertEquals(0, sql.count("select count(*) from search_history where user_id=?", ALICE));
  }

  @Test
  void recallClearsMessageContentAndRejectsLateOrForeignRecall() {
    // Messages use the database clock for created_at; align the injectable application clock
    // with real time so the two-minute recall window is exercised rather than exceeded.
    clock.set(Instant.now());
    UUID a = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, a, apply(BOB, a)).id("groupId");
    Row first =
        chat.send(
            BOB,
            group,
            Row.of("clientMessageId", UUID.randomUUID(), "body", "这条消息会被撤回"));
    chat.recall(BOB, first.id("id"));
    Row recalled =
        ((List<?>) chat.messages(ALICE, group, null, 20).get("items"))
            .stream()
            .map(x -> new Row((Map<String, Object>) x))
            .filter(x -> x.id("id").equals(first.id("id")))
            .findFirst()
            .orElseThrow();
    assertNull(recalled.get("body"));
    assertNotNull(recalled.get("recalledAt"));
    problem("ALREADY_RECALLED", () -> chat.recall(BOB, first.id("id")));
    problem("FORBIDDEN", () -> chat.recall(ALICE, first.id("id")));

    Row second =
        chat.send(
            BOB,
            group,
            Row.of("clientMessageId", UUID.randomUUID(), "body", "两分钟后不能撤回"));
    clock.plus(121);
    problem("RECALL_EXPIRED", () -> chat.recall(BOB, second.id("id")));
    assertNull(sql.must("select body from message where id=?", first.id("id")).get("body"));
  }

  @Test
  void dissolvedGroupBlocksNewJoinsAndRevocationRestoresLiveWrites() {
    UUID a = activity(ALICE, 4, 7200, 10800);
    UUID group = approve(ALICE, a, apply(BOB, a)).id("groupId");
    Row pending = apply(CARA, a);

    UUID report =
        governance
            .report(
                ALICE,
                Row.of(
                    "targetType",
                    "ACTIVITY",
                    "targetId",
                    a,
                    "reason",
                    "烟测：核实小组治理",
                    "evidence",
                    "虚拟证据"))
            .id("reportId");
    governance.phase(ADMIN, report, Row.of("expectedVersion", 0, "reason", "已受理"), true);
    governance.phase(ADMIN, report, Row.of("expectedVersion", 1, "reason", "核实中"), false);
    governance.decide(
        ADMIN,
        report,
        Row.of(
            "dispositionId",
            UUID.randomUUID(),
            "expectedVersion",
            2,
            "action",
            "DISSOLVE_GROUP",
            "reason",
            "核验后解散小组",
            "evidenceReviewed",
            true));

    assertEquals("BLOCKED", team.group(a).text("accessMode"));
    problem("GROUP_BLOCKED", () -> apply(DAN, a));
    problem("GROUP_BLOCKED", () -> approve(ALICE, a, pending));
    assertTrue(sql.count("select count(*) from notification where type='GROUP_DISSOLVED'") >= 1);
    problem("RESOURCE_NOT_FOUND", () -> chat.messages(BOB, group, null, 20));

    UUID appeal =
        governance
            .appeal(ALICE, report, Row.of("reason", "处置有误，申请复核"))
            .id("appealId");
    governance.review(
        ADMIN,
        appeal,
        Row.of("expectedVersion", 0, "result", "REVOKED", "reason", "撤销解散"));
    assertEquals("ACTIVE", team.group(a).text("accessMode"));
    approve(ALICE, a, pending);
    assertEquals(3, team.occupied(a));
    assertNotNull(chat.messages(BOB, group, null, 20));
  }

  @Test
  void notificationPreferencesControlFeedbackGroupAndPendingReminders() {
    UUID a = activity(ALICE, 3, 7200, 10800);
    UUID group = approve(ALICE, a, apply(BOB, a)).id("groupId");
    notifications.updatePreferences(
        ALICE, Row.of("groupMessage", false, "feedbackReminder", false));

    chat.send(BOB, group, Row.of("clientMessageId", UUID.randomUUID(), "body", "群消息"));
    assertEquals(
        0,
        sql.count(
            "select count(*) from notification where type='GROUP_MESSAGE' and recipient_id=?",
            ALICE));

    clock.set(activities.find(a).time("endAt"));
    activities.advance(a);
    assertEquals(
        0,
        sql.count(
            "select count(*) from notification where type='FEEDBACK_REQUEST' and recipient_id=?",
            ALICE));
    assertEquals(
        1,
        sql.count(
            "select count(*) from notification where type='FEEDBACK_REQUEST' and recipient_id=?",
            BOB));

    UUID b = activity(ALICE, 3, 7200, 10800);
    apply(CARA, b);
    notifications.updatePreferences(ALICE, Row.of("pendingReminder", false));
    clock.set(activities.find(b).time("deadline").minusSeconds(600));
    activities.advance(b);
    assertEquals(
        0,
        sql.count(
            "select count(*) from notification where type='PENDING_REMINDER' and recipient_id=?",
            ALICE));
    notifications.updatePreferences(ALICE, Row.of("pendingReminder", true));
    activities.advance(b);
    assertEquals(
        1,
        sql.count(
            "select count(*) from notification where type='PENDING_REMINDER' and recipient_id=?",
            ALICE));
  }

  @Test
  void erasureRemovesV3AuxiliaryDataAndKeepsAnonymisedHistory() {
    UUID a = activity(ALICE, 3, 7200, 10800);
    activities.search(BOB, Row.of("q", "secret-keyword"), false);
    activities.favorite(BOB, a);
    notifications.updatePreferences(BOB, Row.of("groupMessage", false));
    apply(BOB, a);
    assertTrue(sql.count("select count(*) from search_history where user_id=?", BOB) > 0);
    assertTrue(sql.count("select count(*) from activity_favorite where user_id=?", BOB) > 0);
    assertTrue(sql.count("select count(*) from notification_preference where user_id=?", BOB) > 0);
    assertTrue(sql.count("select count(*) from notification where recipient_id=?", BOB) > 0);

    identity.erase(BOB);
    assertEquals(0, sql.count("select count(*) from search_history where user_id=?", BOB));
    assertEquals(0, sql.count("select count(*) from activity_favorite where user_id=?", BOB));
    assertEquals(0, sql.count("select count(*) from notification_preference where user_id=?", BOB));
    assertEquals(0, sql.count("select count(*) from notification where recipient_id=?", BOB));
    assertEquals(0, sql.count("select count(*) from delivery_task where recipient_id=?", BOB));
    assertEquals(0, sql.count("select count(*) from idempotency_record where actor_id=?", BOB));
    assertEquals(1, sql.count("select count(*) from erasure_log where user_id=?", BOB));
    assertEquals("DELETED", sql.must("select status from user_account where id=?", BOB).text("status"));
  }

  @Test
  void favoritesListAndClientSuppliedCiphertextIsIgnored() {
    Row body = body("收藏测试", 3, clock.instant().plusSeconds(7200), clock.instant().plusSeconds(10800));
    body.remove("exactLocation");
    body.put("exactLocationCiphertext", "client-controlled-ciphertext");
    UUID a = activities.publish(ALICE, body, UUID.randomUUID()).id("id");
    assertNull(activities.find(a).get("exactLocationCiphertext"));

    activities.favorite(ALICE, a);
    Row page = activities.favorites(ALICE, null, 20);
    assertEquals(1, ((List<?>) page.get("items")).size());
    assertEquals(a, new Row((Map<String, Object>) ((List<?>) page.get("items")).getFirst()).id("id"));
    activities.unfavorite(ALICE, a);
    assertTrue(((List<?>) activities.favorites(ALICE, null, 20).get("items")).isEmpty());

    activities.change(
        ALICE,
        a,
        Row.of(
            "expectedVersion", 0,
            "title", "收藏测试改名",
            "exactLocationCiphertext", "另一段客户端密文"));
    assertNull(activities.find(a).get("exactLocationCiphertext"));
  }
}
