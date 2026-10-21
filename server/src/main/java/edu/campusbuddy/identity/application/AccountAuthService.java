package edu.campusbuddy.identity.application;

import edu.campusbuddy.identity.infrastructure.IdentityRepository;
import edu.campusbuddy.shared.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("formal")
public class AccountAuthService {
  public record Login(UUID userId, long version) {}
  private final Sql sql;
  private final IdentityRepository identities;
  private final PasswordEncoder passwords = AuthPasswords.encoder();
  private final String dummyHash = passwords.encode(UUID.randomUUID().toString());

  public AccountAuthService(Sql sql, IdentityRepository identities) {
    this.sql = sql;
    this.identities = identities;
  }

  @Transactional
  public Login register(Map<String, Object> body) {
    fields(body, Set.of("username", "password", "nickname"));
    String username = AuthPasswords.username(body.get("username"));
    Problem.require(body.get("nickname") instanceof String, 400, "VALIDATION_FAILED", "请填写昵称");
    String nickname = Input.text(body, "nickname", 40, true);
    String hash = passwords.encode(AuthPasswords.password(body.get("password")));
    UUID user = UUID.randomUUID();
    try {
      sql.update("insert into user_account(id,nickname,role,verified,demo) values(?,?,'USER',false,false)", user, nickname);
      sql.update("insert into account_credential(user_id,username,password_hash) values(?,?,?)", user, username, hash);
      sql.update("insert into privacy_setting(user_id) values(?)", user);
    } catch (DuplicateKeyException e) {
      throw new Problem(409, "USERNAME_UNAVAILABLE", "该用户名不可用");
    }
    return new Login(user, 1);
  }

  public Login login(Map<String, Object> body) {
    fields(body, Set.of("username", "password"));
    String username = AuthPasswords.username(body.get("username"));
    String password = AuthPasswords.password(body.get("password"));
    Row credential = sql.one("select c.user_id,c.password_hash,c.session_version,u.status,u.demo from account_credential c join user_account u on u.id=c.user_id where c.username=?", username);
    boolean matches = passwords.matches(password, credential == null ? dummyHash : credential.text("passwordHash"));
    Problem.require(matches && credential != null && !credential.bool("demo") && !credential.text("status").equals("DELETED"),
        401, "INVALID_CREDENTIALS", "用户名或密码不正确");
    // A concurrent password change invalidates this version even if this request later saves a session.
    return new Login(credential.id("userId"), ((Number) credential.get("sessionVersion")).longValue());
  }

  @Transactional
  public void changePassword(UUID user, Map<String, Object> body) {
    fields(body, Set.of("currentPassword", "newPassword"));
    String current = AuthPasswords.password(body.get("currentPassword"));
    String next = AuthPasswords.password(body.get("newPassword"));
    Problem.require(!current.equals(next), 400, "VALIDATION_FAILED", "新密码不能与原密码相同");
    // Same user-before-credential lock order as account erasure.
    identities.lock(user);
    Row credential = sql.one("select password_hash from account_credential where user_id=? for update", user);
    Problem.require(credential != null && passwords.matches(current, credential.text("passwordHash")),
        401, "INVALID_CREDENTIALS", "当前密码不正确");
    sql.update("update account_credential set password_hash=?,password_change_required=false,session_version=session_version+1,updated_at=now() where user_id=?", passwords.encode(next), user);
    identities.revokeSessions(user);
  }

  private static void fields(Map<String, Object> body, Set<String> allowed) {
    Problem.require(allowed.containsAll(body.keySet()), 400, "VALIDATION_FAILED", "请求包含不支持的字段");
  }
}
