package edu.campusbuddy.activity.api;

import edu.campusbuddy.shared.Row;
import java.util.*;

public interface ActivityApi {
  Row lockParticipants(UUID id, Collection<UUID> extraUsers);

  Row find(UUID id);

  Row lock(UUID id);

  Row share(UUID id);

  Row lockOpen(UUID id);

  void advance(UUID id);

  List<UUID> due();

  void hide(UUID actor, UUID id, boolean hidden, String reason);

  void cancelByGovernance(UUID actor, UUID id, String reason);

  void scrubOwner(UUID id);
}
