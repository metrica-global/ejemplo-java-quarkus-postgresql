package com.zonacards.kernel;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The base of the internal library must stay free of frameworks: errx depends on nothing but the
 * JDK, and kernel depends only on errx. Written as an allow-list, so a new dependency fails here.
 */
class LibraryPurityTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.zonacards.errx", "com.zonacards.kernel");

  @Test
  void classesAreImported() {
    assertFalse(CLASSES.isEmpty());
  }

  @Test
  void errxDependsOnlyOnTheJdk() {
    classes()
        .that()
        .resideInAPackage("com.zonacards.errx..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", "com.zonacards.errx..")
        .check(CLASSES);
  }

  @Test
  void kernelDependsOnlyOnErrx() {
    classes()
        .that()
        .resideInAPackage("com.zonacards.kernel..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", "com.zonacards.errx..", "com.zonacards.kernel..")
        .check(CLASSES);
  }
}
