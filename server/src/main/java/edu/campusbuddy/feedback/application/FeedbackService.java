package edu.campusbuddy.feedback.application;

import edu.campusbuddy.activity.api.*;
import edu.campusbuddy.feedback.infrastructure.*;
import edu.campusbuddy.shared.*;
import edu.campusbuddy.team.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
  private final FeedbackRepository repo;
  private final TeamApi team;
  private final ActivityApi activities;

  public FeedbackService(FeedbackRepository r, TeamApi t, ActivityApi a) {
    repo = r;
    team = t;
    activities = a;
  }

  @Transactional
  public Row eligible(UUID actor, UUID activity) {
    activities.advance(activity);
    Row grant = team.feedbackGrant(actor, activity, List.of());
    grant.put("feedback", repo.own(activity, actor));
    return grant;
  }

  @Transactional
  public Row submit(UUID actor, UUID activity, Map<String, Object> b) {
    activities.advance(activity);
    String completion = Input.choice(b, "completion", "COMPLETED", "PARTIAL", "NOT_COMPLETED"),
        reason = Input.text(b, "reason", 500, !completion.equals("COMPLETED"));
    int experience = (int) Input.number(b, "experience", 1, 5);
    Object raw = b.getOrDefault("ratings", List.of());
    Problem.require(raw instanceof List<?>, 400, "VALIDATION_FAILED", "ratings必须是数组");
    List<Row> ratings = new ArrayList<>();
    Set<UUID> targets = new HashSet<>();
    for (Object o : (List<?>) raw) {
      Problem.require(o instanceof Map<?, ?>, 400, "VALIDATION_FAILED", "评价格式不合法");
      Row rt = new Row((Map<String, Object>) o);
      UUID target = Input.uuid(rt.get("targetUserId"));
      Problem.require(targets.add(target), 400, "INVALID_RATING_TARGET", "评价对象重复");
      Input.number(rt, "score", 1, 5);
      Input.text(rt, "comment", 300, false);
      ratings.add(rt);
    }
    team.feedbackGrant(actor, activity, targets);
    Problem.require(repo.own(activity, actor) == null, 409, "ALREADY_SUBMITTED", "每个活动只可反馈一次");
    UUID id = repo.create(activity, actor, completion, reason, experience);
    for (Row rt : ratings)
      repo.rate(id, rt.id("targetUserId"), (int) rt.num("score"), rt.text("comment"));
    return Row.of("feedbackId", id);
  }
}
