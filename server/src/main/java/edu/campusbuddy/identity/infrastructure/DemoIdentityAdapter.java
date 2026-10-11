package edu.campusbuddy.identity.infrastructure;

import edu.campusbuddy.identity.api.IdentityAdapter;
import edu.campusbuddy.shared.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DemoIdentityAdapter implements IdentityAdapter {
  private final IdentityRepository repo;

  public DemoIdentityAdapter(IdentityRepository r) {
    repo = r;
  }

  public Row authenticate(UUID id) {
    Row u = repo.user(id);
    Problem.require(
        u.bool("demo") && !u.text("status").equals("DELETED"), 403, "FORBIDDEN", "测试身份不可用");
    return u.only("id", "nickname", "role", "verified", "status");
  }

  public List<Row> accounts() {
    return repo.demoUsers();
  }
}
