package edu.campusbuddy.team.api;

import edu.campusbuddy.shared.Row;
import java.time.*;
import java.util.*;

public interface TeamApi {
  boolean inSnapshot(UUID activity, UUID user);

  List<UUID> activeUsers(UUID activity);

  long occupied(UUID activity);

  void requireNoConflict(UUID user, Instant from, Instant to, UUID exclude);

  void addOwner(UUID activity, UUID user);

  void closePending(Row activity, String state);

  void changed(Row activity, boolean reset);

  void ended(Row activity, Instant endedAt);

  void readOnly(UUID activity);

  void block(UUID activity, boolean value);

  Row grant(UUID actor, UUID group, boolean write);

  Row group(UUID activity);

  List<Row> members(UUID actor, UUID activity);

  Row feedbackGrant(UUID actor, UUID activity, Collection<UUID> targets);

  Row remove(UUID actor, UUID activity, UUID user, String reason, boolean governance);

  void restoreRemoved(UUID activity, UUID user);

  List<UUID> affected(UUID activity);
}
