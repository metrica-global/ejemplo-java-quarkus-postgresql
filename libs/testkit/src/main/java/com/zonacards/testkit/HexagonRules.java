package com.zonacards.testkit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The architecture of a domain, as executable rules. Every service runs them in its build, so a
 * dependency that crosses a boundary fails the pull request instead of being found in a review.
 *
 * <p>The core (domain, events, services) is checked with an <b>allow-list</b>: it may depend on
 * the JDK and the internal library, and on nothing else. A new framework import fails without
 * anyone having to remember to forbid it. Adapters are checked with a deny-list, because using
 * third-party libraries is exactly their job.
 *
 * <pre>
 * &lt;base&gt;            domain: aggregate, requests, errors, ports
 * &lt;base&gt;.events     event contracts
 * &lt;base&gt;.srv        use cases and event handlers
 * &lt;base&gt;.infra      driven adapters (database, crypto)
 * &lt;base&gt;.api        driving adapter (REST)
 * &lt;base&gt;.container  composition root: the only package that sees everything
 * </pre>
 */
public final class HexagonRules {

  private static final String[] INTERNAL_LIBRARY = {
    "java..",
    "com.zonacards.kernel..",
    "com.zonacards.errx..",
    "com.zonacards.logx..",
    "com.zonacards.ptrx..",
    "com.zonacards.asyncx.."
  };

  private HexagonRules() {}

  /** Imports the production classes of the domain and checks every rule. */
  public static void check(String base, String... otherDomains) {
    JavaClasses classes =
        new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(base);
    if (classes.isEmpty()) {
      throw new AssertionError("no classes found under " + base);
    }
    check(base, classes, otherDomains);
  }

  public static void check(String base, JavaClasses classes, String... otherDomains) {
    for (ArchRule rule : rules(base, otherDomains)) {
      rule.check(classes);
    }
  }

  public static List<ArchRule> rules(String base, String... otherDomains) {
    List<ArchRule> rules = new ArrayList<>();
    rules.add(
        classes()
            .that()
            .resideInAPackage(base)
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(allow(base))
            .as("the domain depends only on the JDK and on the framework-free internal library")
            .allowEmptyShould(true));
    rules.add(
        classes()
            .that()
            .resideInAPackage(base + ".events..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", base + ".events..")
            .as("event contracts are plain data: they depend only on the JDK")
            .allowEmptyShould(true));
    rules.add(
        classes()
            .that()
            .resideInAPackage(base + ".srv..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(allow(base, base + ".events..", base + ".srv..", "com.zonacards.eventx"))
            .as("services depend only on the domain, its events and the ports of eventx")
            .allowEmptyShould(true));
    rules.add(
        noClasses()
            .that()
            .resideInAPackage(base + ".infra..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(base + ".api..", base + ".srv..", base + ".container..", "jakarta.ws.rs..", "io.quarkus..")
            .as("driven adapters implement ports; they know neither the API, nor the services, nor the framework")
            .allowEmptyShould(true));
    rules.add(
        noClasses()
            .that()
            .resideInAPackage(base + ".api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                base + ".infra..", base + ".container..", "com.zonacards.dbx..", "com.zonacards.eventx..", "java.sql..", "javax.sql..")
            .as("the REST adapter talks to services; it never reaches the database or the broker")
            .allowEmptyShould(true));
    rules.add(
        noClasses()
            .that()
            .resideInAPackage(base + "..")
            .and()
            .resideOutsideOfPackage(base + ".container..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(base + ".container..")
            .as("nothing depends on the composition root")
            .allowEmptyShould(true));
    rules.add(
        noClasses()
            .that()
            .resideInAPackage(base + "..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("jakarta.transaction.Transactional")
            .as("transaction boundaries are explicit (UnitOfWork), never an annotation")
            .allowEmptyShould(true));
    if (otherDomains.length > 0) {
      rules.add(
          noClasses()
              .that()
              .resideInAPackage(base + "..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(otherDomains)
              .as("a domain never imports another domain: they only share events")
              .allowEmptyShould(true));
    }
    return rules;
  }

  private static String[] allow(String... more) {
    List<String> all = new ArrayList<>(Arrays.asList(INTERNAL_LIBRARY));
    all.addAll(Arrays.asList(more));
    return all.toArray(String[]::new);
  }
}
