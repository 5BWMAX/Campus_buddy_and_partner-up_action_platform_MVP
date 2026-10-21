package edu.campusbuddy.identity.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

/** Offline administrator creation. No HTTP bootstrap endpoint and no password in arguments. */
public final class AdminBootstrap {
  private AdminBootstrap() {}

  public static void main(String[] args) {
    try {
      run(args);
    } catch (Exception e) {
      System.err.println("Administrator initialization failed (" + e.getClass().getSimpleName()
          + "). Check prerequisites and existing accounts; no credentials were printed.");
      System.exit(1);
    }
  }

  private static void run(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Usage: AdminBootstrap USERNAME NEW_PRIVATE_OUTPUT_FILE");
    String username = AuthPasswords.username(args[0]);
    Path output = Path.of(args[1]).toAbsolutePath();
    // The deployment utility is deliberately Linux-only: create the file with 0600, not chmod later.
    if (!Files.getFileStore(output.getParent()).supportsFileAttributeView("posix"))
      throw new IllegalStateException("Administrator output requires a POSIX filesystem with owner-only permissions");
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    String hash = AuthPasswords.encoder().encode(password);
    Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    boolean committed = false;
    try (Connection connection = DriverManager.getConnection(required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"))) {
      connection.setAutoCommit(false);
      try {
        create(connection, username, hash);
        Files.writeString(output, "USERNAME=" + username + "\nTEMPORARY_PASSWORD=" + password + "\n", StandardCharsets.UTF_8);
        connection.commit();
        committed = true;
      } catch (Exception e) {
        connection.rollback();
        throw e;
      }
    } finally {
      // Retain the protected file on failure as well: a commit acknowledgement may have been lost.
      // Never emit JDBC errors, which can include deployment details or bound values.
      System.out.println(committed ? "Administrator initialized; change the temporary password at first login."
          : "Initialization did not complete normally. Inspect the database before retrying; the private output file was retained.");
    }
  }

  public static void create(Connection connection, String username, String hash) throws SQLException {
    if (connection.getAutoCommit()) throw new IllegalStateException("Administrator initialization requires a transaction");
    username = AuthPasswords.username(username);
    try (Statement statement = connection.createStatement()) {
      statement.execute("select pg_advisory_xact_lock(1827364521)");
      try (ResultSet result = statement.executeQuery("select count(*) from user_account where role='ADMIN' and status<>'DELETED'")) {
        result.next();
        if (result.getLong(1) != 0) throw new IllegalStateException("An administrator already exists; refusing to overwrite accounts");
      }
    }
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement = connection.prepareStatement("insert into user_account(id,nickname,role,verified,demo) values(?,'平台管理员','ADMIN',false,false)")) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement("insert into account_credential(user_id,username,password_hash,password_change_required) values(?,?,?,true)")) {
      statement.setObject(1, id);
      statement.setString(2, username);
      statement.setString(3, hash);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement("insert into privacy_setting(user_id) values(?)")) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }

  private static String required(String name) {
    return Objects.requireNonNull(System.getenv(name), "Missing environment variable: " + name);
  }
}
