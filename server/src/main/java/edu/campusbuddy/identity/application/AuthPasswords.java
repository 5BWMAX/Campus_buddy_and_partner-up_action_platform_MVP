package edu.campusbuddy.identity.application;

import edu.campusbuddy.shared.Problem;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

public final class AuthPasswords {
  private AuthPasswords() {}

  public static PasswordEncoder encoder() {
    Pbkdf2PasswordEncoder pbkdf2 = new Pbkdf2PasswordEncoder("", 16, 600000,
        Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    return new DelegatingPasswordEncoder("pbkdf2-sha256-v1", Map.of("pbkdf2-sha256-v1", pbkdf2));
  }

  public static String username(Object value) {
    Problem.require(value instanceof String, 400, "VALIDATION_FAILED", "请填写用户名");
    String username = (String) value;
    Problem.require(username.matches("[A-Za-z0-9_]{3,32}"), 400, "VALIDATION_FAILED",
        "用户名需为3–32位英文字母、数字或下划线");
    return username.toLowerCase(Locale.ROOT);
  }

  public static String password(Object value) {
    Problem.require(value instanceof String, 400, "VALIDATION_FAILED", "请填写密码");
    String password = (String) value;
    int length = password.codePointCount(0, password.length());
    Problem.require(length >= 15 && length <= 128, 400, "VALIDATION_FAILED", "密码长度需为15–128个字符");
    return password;
  }
}
