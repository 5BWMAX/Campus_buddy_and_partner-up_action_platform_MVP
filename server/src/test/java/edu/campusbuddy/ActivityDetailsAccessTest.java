package edu.campusbuddy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ActivityDetailsAccessTest extends PgIntegrationSupport {
  @MockitoSpyBean JdbcTemplate countedJdbc;

  @Test
  void detailsUseSingleUserAccessQueryWithoutLoadingMembers() {
    UUID activity = activity(ALICE, 4, 7200, 10800);
    UUID group = approve(ALICE, activity, apply(BOB, activity)).id("groupId");
    approve(ALICE, activity, apply(CARA, activity));
    identity.privacy(ALICE, Row.of("contactVisibility", "HIDDEN", "locationVisibility", "GROUP"));

    clearInvocations(countedJdbc);
    Row details = activities.details(BOB, activity);
    List<String> queries = queries();

    assertEquals(group, details.id("groupId"));
    assertEquals("三楼东侧", details.text("exactLocation"));
    assertEquals(3, details.num("occupied"));
    assertEquals(1, details.num("remaining"));
    assertEquals(detailFields("groupId", "exactLocation"), details.keySet());
    assertEquals(8, queries.size());
    assertEquals(1, queries.stream().filter(q -> q.contains("activity_favorite")).count());
    assertFalse(details.bool("favorited"));
    assertEquals(
        1,
        queries.stream()
            .filter(q -> q.contains("left join temporary_group") && q.contains("p.user_id=?"))
            .count());
    assertEquals(
        2,
        queries.stream()
            .filter(q -> q.contains("select count(*) from participation"))
            .count());
    assertFalse(queries.stream().anyMatch(q -> q.contains("order by user_id")));
    assertFalse(queries.stream().anyMatch(q -> q.contains("where u.id in (")));
  }

  @Test
  void ownerWithoutGroupAndMembersInTerminalActivitiesKeepAccess() {
    UUID withoutGroup = activity(ALICE, 1, 7200, 10800);
    Row ownerDetails = activities.details(ALICE, withoutGroup);
    assertFalse(ownerDetails.containsKey("groupId"));
    assertEquals("三楼东侧", ownerDetails.text("exactLocation"));
    assertEquals(detailFields("exactLocation"), ownerDetails.keySet());

    identity.privacy(ALICE, Row.of("contactVisibility", "HIDDEN", "locationVisibility", "GROUP"));
    long start = 14400;
    for (String status : List.of("ENDED", "CANCELLED")) {
      UUID terminal = activity(ALICE, 2, start, start + 3600);
      UUID group = approve(ALICE, terminal, apply(BOB, terminal)).id("groupId");
      jdbc.update("update activity set status=? where id=?", status, terminal);

      Row memberDetails = activities.details(BOB, terminal);
      assertEquals(status, memberDetails.text("status"));
      assertEquals(group, memberDetails.id("groupId"));
      assertEquals("三楼东侧", memberDetails.text("exactLocation"));
      start += 7200;
    }
  }

  @Test
  void nonMembersAndExitedMembersKeepPublicDetailsOnly() {
    UUID activity = activity(ALICE, 2, 7200, 10800);
    approve(ALICE, activity, apply(BOB, activity));
    identity.privacy(ALICE, Row.of("contactVisibility", "HIDDEN", "locationVisibility", "GROUP"));

    problem("RESOURCE_NOT_FOUND", () -> team.requireActivityDetailsAccess(CARA, activity));
    assertPublicOnly(activities.details(CARA, activity));

    team.exit(BOB, activity, Row.of("reason", "退出"), UUID.randomUUID());
    problem("RESOURCE_NOT_FOUND", () -> team.requireActivityDetailsAccess(BOB, activity));
    assertPublicOnly(activities.details(BOB, activity));
  }

  @Test
  void blockedGroupsAndAbnormalUsersKeepPublicDetailsOnly() {
    UUID blocked = activity(ALICE, 2, 7200, 10800);
    UUID blockedGroup = approve(ALICE, blocked, apply(BOB, blocked)).id("groupId");
    identity.privacy(ALICE, Row.of("contactVisibility", "HIDDEN", "locationVisibility", "GROUP"));
    jdbc.update("update temporary_group set access_mode='BLOCKED' where id=?", blockedGroup);

    problem("RESOURCE_NOT_FOUND", () -> team.requireActivityDetailsAccess(BOB, blocked));
    assertPublicOnly(activities.details(BOB, blocked));

    UUID disabled = activity(ALICE, 2, 14400, 18000);
    approve(ALICE, disabled, apply(BOB, disabled));
    jdbc.update("update user_account set status='DISABLED' where id=?", BOB);

    problem("RESOURCE_NOT_FOUND", () -> team.requireActivityDetailsAccess(BOB, disabled));
    assertPublicOnly(activities.details(BOB, disabled));
  }

  private void assertPublicOnly(Row details) {
    assertFalse(details.containsKey("groupId"));
    assertFalse(details.containsKey("exactLocation"));
    assertTrue(details.containsKey("occupied"));
    assertTrue(details.containsKey("remaining"));
    assertEquals(detailFields(), details.keySet());
  }

  private Set<String> detailFields(String... protectedFields) {
    Set<String> fields =
        new HashSet<>(
            List.of(
                "id",
                "ownerId",
                "type",
                "title",
                "description",
                "campus",
                "tags",
                "startAt",
                "endAt",
                "deadline",
                "confirmationDeadline",
                "endedAt",
                "capacity",
                "status",
                "terminalState",
                "version",
                "publicLocation",
                "publicLat",
                "publicLon",
                "occupied",
                "favorited",
                "remaining"));
    fields.addAll(List.of(protectedFields));
    return fields;
  }

  private List<String> queries() {
    Class<?>[] signature = {String.class, RowMapper.class, Object[].class};
    return mockingDetails(countedJdbc).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("query"))
        .filter(
            invocation ->
                Arrays.equals(invocation.getMethod().getParameterTypes(), signature))
        .map(invocation -> invocation.getArgument(0))
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .map(query -> query.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
        .toList();
  }
}
