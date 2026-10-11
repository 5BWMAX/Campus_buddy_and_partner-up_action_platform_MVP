package edu.campusbuddy.feedback.infrastructure;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {
  private final Sql sql;

  public FeedbackRepository(Sql s) {
    sql = s;
  }

  public Row own(UUID activity, UUID user) {
    return sql.one("select * from feedback where activity_id=? and user_id=?", activity, user);
  }

  public UUID create(UUID activity, UUID user, String completion, String reason, int experience) {
    UUID id = UUID.randomUUID();
    sql.update(
        "insert into feedback(id,activity_id,user_id,completion,reason,experience)"
            + " values(?,?,?,?,?,?)",
        id,
        activity,
        user,
        completion,
        reason,
        experience);
    return id;
  }

  public void rate(UUID id, UUID target, int score, String comment) {
    sql.update(
        "insert into peer_rating(id,feedback_id,target_user_id,score,comment) values(?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        target,
        score,
        comment);
  }
}
