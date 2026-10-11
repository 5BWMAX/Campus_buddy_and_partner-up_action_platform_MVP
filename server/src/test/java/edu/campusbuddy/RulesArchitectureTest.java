package edu.campusbuddy;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import edu.campusbuddy.activity.domain.ActivityRules;
import edu.campusbuddy.shared.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RulesArchitectureTest {
  @Test
  void halfOpenIntervalsAndDeadlineBoundary() {
    Instant now = Instant.parse("2030-01-01T00:00:00Z");
    assertFalse(
        ActivityRules.overlap(now, now.plusSeconds(10), now.plusSeconds(10), now.plusSeconds(20)));
    assertTrue(
        ActivityRules.overlap(now, now.plusSeconds(11), now.plusSeconds(10), now.plusSeconds(20)));
    Row a = Row.of("status", "OPEN", "visibility", "VISIBLE", "deadline", now);
    assertThrows(Problem.class, () -> ActivityRules.open(a, now));
    ActivityRules.open(a, now.minusNanos(1));
  }

  @Test
  void sevenModulesOnlyUseOtherModulesThroughApi() {
    var classes = new ClassFileImporter().importPackages("edu.campusbuddy");
    var modules =
        List.of("identity", "activity", "team", "chat", "feedback", "notification", "governance");
    for (String from : modules)
      for (String to : modules)
        if (!from.equals(to))
          noClasses()
              .that()
              .resideInAPackage("edu.campusbuddy." + from + "..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "edu.campusbuddy." + to + ".application..",
                  "edu.campusbuddy." + to + ".domain..",
                  "edu.campusbuddy." + to + ".infrastructure..")
              .check(classes);
  }
}
