package edu.campusbuddy.identity.api;

import edu.campusbuddy.shared.Row;
import java.util.*;

public interface IdentityApi {
  record UserGrant(UUID id, boolean verified, String status, String role) {}

  UserGrant check(UUID id);

  UserGrant requireVerified(UUID id);

  void requireAdmin(UUID id);

  void lockUsers(Collection<UUID> ids);

  Row profile(UUID id);

  Row memberProfile(UUID id);

  void disable(UUID id, boolean disabled);

  boolean exists(UUID id);

  List<UUID> administrators();
}
