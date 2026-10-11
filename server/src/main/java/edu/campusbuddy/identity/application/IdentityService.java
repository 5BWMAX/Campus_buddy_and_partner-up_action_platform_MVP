package edu.campusbuddy.identity.application;

import edu.campusbuddy.identity.api.*;
import edu.campusbuddy.identity.infrastructure.*;
import edu.campusbuddy.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class IdentityService implements IdentityApi {
  private final IdentityRepository repo;
  private final Crypto crypto;
  private final edu.campusbuddy.activity.api.ActivityApi activities;

  public IdentityService(
      IdentityRepository r,
      Crypto c,
      @org.springframework.context.annotation.Lazy edu.campusbuddy.activity.api.ActivityApi a) {
    repo = r;
    crypto = c;
    activities = a;
  }

  public UserGrant check(UUID id) {
    Problem.require(id != null, 401, "AUTH_REQUIRED", "请先登录");
    Row u = repo.user(id);
    Problem.require(
        u.text("status").equals("NORMAL"), 403, "ACCOUNT_DISABLED", "账号已受限，仅能查看相关举报和申诉");
    return new UserGrant(id, u.bool("verified"), u.text("status"), u.text("role"));
  }

  public UserGrant requireVerified(UUID id) {
    UserGrant u = check(id);
    Problem.require(u.verified(), 403, "IDENTITY_REQUIRED", "需要已验证的校内身份");
    return u;
  }

  public void requireAdmin(UUID id) {
    Problem.require(check(id).role().equals("ADMIN"), 403, "FORBIDDEN", "需要管理员权限");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void lockUsers(Collection<UUID> ids) {
    ids.stream().distinct().sorted(Comparator.comparing(UUID::toString)).forEach(repo::lock);
  }

  public List<UUID> administrators() {
    return repo.administrators();
  }

  public boolean exists(UUID id) {
    return repo.exists(id);
  }

  public Row profile(UUID id) {
    Row u = repo.user(id);
    Row out =
        u.only(
            "id",
            "nickname",
            "avatarUrl",
            "department",
            "role",
            "verified",
            "status",
            "contactVisibility",
            "locationVisibility");
    if (!u.text("status").equals("DELETED"))
      out.put("contact", crypto.decrypt(u.text("contactCiphertext")));
    return out;
  }

  public Row memberProfile(UUID id) {
    Row u = repo.user(id);
    Row out = u.only("id", "nickname", "avatarUrl", "department");
    if (u.text("status").equals("NORMAL") && u.text("contactVisibility").equals("GROUP"))
      out.put("contact", crypto.decrypt(u.text("contactCiphertext")));
    return out;
  }

  @Transactional
  public Row update(UUID id, Map<String, Object> b) {
    lockUsers(List.of(id));
    check(id);
    Row old = profile(id);
    Row merged = new Row(old);
    merged.putAll(b);
    String avatar = Input.text(merged, "avatarUrl", 512, false);
    Problem.require(
        avatar.isEmpty() || avatar.matches("https://[^\\s]+"),
        400,
        "VALIDATION_FAILED",
        "头像使用HTTPS地址");
    repo.profile(
        id,
        Input.text(merged, "nickname", 40, true),
        avatar,
        Input.text(merged, "department", 80, false),
        crypto.encrypt(Input.text(merged, "contact", 200, false)));
    return profile(id);
  }

  @Transactional
  public Row privacy(UUID id, Map<String, Object> b) {
    lockUsers(List.of(id));
    check(id);
    Row m = profile(id);
    m.putAll(b);
    repo.privacy(
        id,
        Input.choice(m, "contactVisibility", "HIDDEN", "GROUP"),
        Input.choice(m, "locationVisibility", "HIDDEN", "GROUP"));
    return profile(id);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void disable(UUID id, boolean disabled) {
    repo.status(id, disabled ? "DISABLED" : "NORMAL");
  }

  @Transactional
  public Row erase(UUID id) {
    lockUsers(List.of(id));
    check(id);
    repo.erase(id);
    activities.scrubOwner(id);
    return Row.of("status", "DELETED");
  }
}
