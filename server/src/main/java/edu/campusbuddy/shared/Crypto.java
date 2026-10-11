package edu.campusbuddy.shared;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Crypto {
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  public Crypto(@Value("${campus.crypto-key}") String k) {
    key = Base64.getDecoder().decode(k);
    if (key.length != 32) throw new IllegalArgumentException("APP_CRYPTO_KEY must be 32 bytes");
  }

  public String encrypt(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      byte[] body = c.doFinal(s.getBytes(StandardCharsets.UTF_8));
      byte[] all = new byte[iv.length + body.length];
      System.arraycopy(iv, 0, all, 0, iv.length);
      System.arraycopy(body, 0, all, iv.length, body.length);
      return Base64.getEncoder().encodeToString(all);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String decrypt(String s) {
    if (s == null || s.isBlank()) return "";
    try {
      byte[] all = Base64.getDecoder().decode(s);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(all, 12)));
      return new String(c.doFinal(Arrays.copyOfRange(all, 12, all.length)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to decrypt protected field");
    }
  }
}
