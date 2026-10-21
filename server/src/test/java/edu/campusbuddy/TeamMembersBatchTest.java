package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class TeamMembersBatchTest extends PgIntegrationSupport {
  @MockitoSpyBean JdbcTemplate countedJdbc;

  @Test
  void memberQueriesStayConstantForOneAndMultipleMembersAndKeepOrderAndParticipationFields() {
    UUID activity = activity(ALICE, 3, 7200, 10800);

    clearInvocations(countedJdbc);
    List<Row> single = team.members(ALICE, activity);
    long singleQueries = memberQueries();
    assertEquals(List.of(ALICE), single.stream().map(x -> x.id("id")).toList());
    assertEquals(6, singleQueries);

    approve(ALICE, activity, apply(CARA, activity));
    approve(ALICE, activity, apply(BOB, activity));
    identity.update(BOB, Row.of("contact", "batch-private-contact"));
    identity.privacy(BOB, Row.of("contactVisibility", "GROUP", "locationVisibility", "HIDDEN"));
    team.confirm(
        BOB,
        activity,
        Row.of("status", "CONFIRMED", "expectedVersion", part(activity, BOB).num("version")));
    clock.plus(7200);
    team.checkin(BOB, activity, Row.of("status", "SELF_REPORTED"));

    clearInvocations(countedJdbc);
    List<Row> multiple = team.members(ALICE, activity);
    long multipleQueries = memberQueries();
    assertEquals(
        List.of(ALICE, BOB, CARA), multiple.stream().map(x -> x.id("id")).toList());
    assertEquals(singleQueries, multipleQueries);

    Row bob = multiple.stream().filter(x -> BOB.equals(x.id("id"))).findFirst().orElseThrow();
    Row stored = part(activity, BOB);
    assertEquals("batch-private-contact", bob.text("contact"));
    for (String field : List.of("role", "confirmation", "version", "checkinStatus", "checkinAt"))
      assertEquals(stored.get(field), bob.get(field));
  }

  @Test
  void hiddenContactAndAbnormalUserStatusKeepExistingMemberProfileRules() {
    UUID activity = activity(ALICE, 2, 7200, 10800);
    approve(ALICE, activity, apply(BOB, activity));
    identity.update(BOB, Row.of("contact", "batch-private-contact"));

    Row hidden = member(team.members(ALICE, activity), BOB);
    assertFalse(hidden.containsKey("contact"));

    identity.privacy(BOB, Row.of("contactVisibility", "GROUP", "locationVisibility", "HIDDEN"));
    assertEquals("batch-private-contact", member(team.members(ALICE, activity), BOB).text("contact"));

    jdbc.update("update user_account set status='DISABLED' where id=?", BOB);
    Row disabled = member(team.members(ALICE, activity), BOB);
    assertEquals("测试同学2", disabled.text("nickname"));
    assertFalse(disabled.containsKey("contact"));
  }

  @Test
  void missingMemberProfileStillReturnsResourceNotFound() {
    UUID activity = activity(ALICE, 2, 7200, 10800);
    approve(ALICE, activity, apply(BOB, activity));
    jdbc.update("delete from privacy_setting where user_id=?", BOB);

    problem("RESOURCE_NOT_FOUND", () -> team.members(ALICE, activity));
  }

  @Test
  void activityWithNoActiveMembersRemainsInaccessible() {
    UUID activity = activity(ALICE, 1, 7200, 10800);
    jdbc.update("delete from participation where activity_id=?", activity);

    problem("RESOURCE_NOT_FOUND", () -> team.members(ALICE, activity));
  }

  private Row member(List<Row> members, UUID id) {
    return members.stream().filter(x -> id.equals(x.id("id"))).findFirst().orElseThrow();
  }

  private long memberQueries() {
    Class<?>[] signature = {String.class, RowMapper.class, Object[].class};
    return mockingDetails(countedJdbc).getInvocations().stream()
        .filter(x -> x.getMethod().getName().equals("query"))
        .filter(x -> Arrays.equals(x.getMethod().getParameterTypes(), signature))
        .count();
  }
}
