package edu.campusbuddy.identity.application;

import edu.campusbuddy.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Profile("formal")
public class AuthAttemptLimiter {
  private final Sql sql;
  private final Clock clock;

  public AuthAttemptLimiter(Sql sql, Clock clock) {
    this.sql = sql;
    this.clock = clock;
  }

  // Independent commit: rejected authentication must not roll back its attempt counter.
  @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = Problem.class)
  public void consume(String operation, String client, String account) {
    Instant now = clock.instant();
    sql.update("delete from auth_attempt_window where expires_at<=?", now);
    consumeKey(operation + ":ip:" + client, operation.equals("register") ? 10 : 60, now);
    if (account != null) consumeKey(operation + ":account:" + account, 20, now);
  }

  private void consumeKey(String value, int limit, Instant now) {
    String key;
    try {
      key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    long attempts = sql.count("insert into auth_attempt_window(bucket_key,expires_at,attempts) values(?,?,1) on conflict(bucket_key) do update set attempts=auth_attempt_window.attempts+1 returning attempts", key, now.plusSeconds(900));
    Problem.require(attempts <= limit, 429, "AUTH_RATE_LIMITED", "尝试过于频繁，请15分钟后重试");
  }
}
