package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;

import edu.campusbuddy.identity.api.AuthClientAddress;
import edu.campusbuddy.identity.application.*;
import edu.campusbuddy.shared.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthConfigurationTest {
  @Test
  void formalAndDemoCannotBeCombined() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("formal", "demo");
    assertThrows(IllegalStateException.class, () -> new AuthMode(environment));
    environment.setActiveProfiles("formal");
    assertTrue(new AuthMode(environment).formal());
  }

  @Test
  void forwardedAddressRequiresExplicitTrustAndALoopbackPeer() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Forwarded-For", "203.0.113.9");
    assertEquals("127.0.0.1", new AuthClientAddress(false).client(request));
    AuthClientAddress trusted = new AuthClientAddress(true);
    assertEquals("203.0.113.9", trusted.client(request));
    request.setRemoteAddr("198.51.100.10");
    assertEquals("198.51.100.10", trusted.client(request));
    request.setRemoteAddr("127.0.0.1");
    for (String invalid : new String[]{"203.0.113.9, 127.0.0.1", "attacker.example", "999.1.1.1", "localhost"}) {
      request.removeHeader("X-Forwarded-For");
      request.addHeader("X-Forwarded-For", invalid);
      assertEquals("127.0.0.1", trusted.client(request));
    }
  }

  @Test
  void passwordWhitespaceIsPreservedAndLengthUsesUnicodeCodePoints() {
    String password = "  test passphrase  ";
    assertEquals(password, AuthPasswords.password(password));
    assertEquals("校园".repeat(8), AuthPasswords.password("校园".repeat(8)));
    assertThrows(Problem.class, () -> AuthPasswords.password("short"));
    assertThrows(Problem.class, () -> AuthPasswords.password("a".repeat(129)));
    assertThrows(Problem.class, () -> AuthPasswords.username("admin@example.org"));
  }
}
