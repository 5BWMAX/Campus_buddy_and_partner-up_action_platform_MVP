package edu.campusbuddy.identity.api;

import edu.campusbuddy.shared.Row;
import java.util.*;

public interface IdentityApi {
  record UserGrant(UUID id, boolean verified, String status, String role) {}

  UserGrant check(UUID id);

  UserGrant requireVerified(UUID id);

  void requireAdmin(UUID id);

  void lockUsers(Collection<UUID> ids);

  void lockUserReferences(Collection<UUID> referenceIds);

  void lockUsersAndReferences(
      Collection<UUID> updateIds, Collection<UUID> referenceOnlyIds);

  Row profile(UUID id);

  Row memberProfile(UUID id);

  Map<UUID, Row> memberProfiles(Collection<UUID> ids);

  void disable(UUID id, boolean disabled);

  boolean exists(UUID id);

  List<UUID> administrators();
}
