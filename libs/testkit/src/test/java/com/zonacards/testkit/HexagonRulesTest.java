package com.zonacards.testkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rules are only worth something if they fail when they should. This proves they do. */
class HexagonRulesTest {

  private static final String GOOD = "com.zonacards.testkit.fixture.good";
  private static final String BAD = "com.zonacards.testkit.fixture.bad";

  @Test
  void aWellFormedDomainPasses() {
    JavaClasses classes = new ClassFileImporter().importPackages(GOOD);
    assertEquals(6, classes.size());
    assertDoesNotThrow(() -> HexagonRules.check(GOOD, classes, BAD + ".."));
  }

  @Test
  void everyKindOfViolationIsCaught() {
    JavaClasses classes = new ClassFileImporter().importPackages(BAD);
    List<String> failed = new ArrayList<>();
    for (ArchRule rule : HexagonRules.rules(BAD, GOOD + "..")) {
      try {
        rule.check(classes);
      } catch (AssertionError e) {
        failed.add(rule.getDescription());
      }
    }
    assertTrue(failed.stream().anyMatch(d -> d.startsWith("the domain depends only")), "domain -> infra not caught: " + failed);
    assertTrue(failed.stream().anyMatch(d -> d.startsWith("services depend only")), "service -> JDBC not caught: " + failed);
    assertTrue(failed.stream().anyMatch(d -> d.startsWith("the REST adapter")), "api -> infra not caught: " + failed);
    assertTrue(failed.stream().anyMatch(d -> d.startsWith("a domain never imports")), "domain -> other domain not caught: " + failed);
    assertThrows(AssertionError.class, () -> HexagonRules.check(BAD, classes));
  }
}
