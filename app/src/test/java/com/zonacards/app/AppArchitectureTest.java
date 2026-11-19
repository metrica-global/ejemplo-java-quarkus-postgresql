package com.zonacards.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.zonacards.server.AppModule;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The shape of the application as executable rules. Each module checks its own hexagon and that
 * it imports no other module (see its ArchitectureTest); these are the rules about the whole.
 */
class AppArchitectureTest {

  private static final JavaClasses APPLICATION =
      new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.zonacards");

  @Test
  void theRootKnowsAModuleOnlyThroughItsContainer() {
    noClasses()
        .that()
        .resideInAPackage("com.zonacards.app..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.zonacards.*.api..", "com.zonacards.*.srv..", "com.zonacards.*.infra..", "com.zonacards.*.events..")
        .as("the root container lists the modules; it never reaches inside one")
        .check(APPLICATION);
  }

  @Test
  void theInternalLibraryKnowsNoModule() {
    noClasses()
        .that()
        .resideInAnyPackage(
            "com.zonacards.kernel..",
            "com.zonacards.errx..",
            "com.zonacards.logx..",
            "com.zonacards.ptrx..",
            "com.zonacards.asyncx..",
            "com.zonacards.dbx..",
            "com.zonacards.eventx..",
            "com.zonacards.server..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.zonacards.app..", "com.zonacards.lotes..", "com.zonacards.giftcards..")
        .as("the internal library is shared by every module and depends on none")
        .check(APPLICATION);
  }

  @Test
  void everyModuleInTheCodeIsListedInTheRootContainer() {
    // A module is a package with a container. One that is not listed would be compiled into
    // the image and never served, whatever ZC_MODULES says.
    Set<String> inTheCode =
        APPLICATION.stream()
            .map(JavaClass::getPackageName)
            .filter(name -> name.matches("com\\.zonacards\\.[a-z]+\\.container"))
            .map(name -> name.split("\\.")[2])
            .collect(Collectors.toCollection(TreeSet::new));

    List<AppModule> listed = new AppContainer().modules(null, null);
    Set<String> names = listed.stream().map(AppModule::name).collect(Collectors.toCollection(TreeSet::new));

    assertEquals(inTheCode, names);
    assertEquals(listed.size(), names.size(), "module names are unique");
    for (AppModule module : listed) {
      assertEquals("/api/v1/" + module.name(), module.apiPath(), "a module's API lives under its own name");
    }
  }
}
