package com.zonacards.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/** {@code zonacards.modules} is the one switch; everything per module is derived from it. */
class ModulesConfigSourceFactoryTest {

  private static SmallRyeConfig config(Map<String, String> properties) {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(new PropertiesConfigSource(properties, "application.properties", 250))
        .withSources(new ModulesConfigSourceFactory())
        .build();
  }

  @Test
  void everyListedModuleIsSwitchedOnAndEveryOtherOneOff() {
    SmallRyeConfig config =
        config(
            Map.of(
                "zonacards.modules", "lotes",
                // The way application.properties activates each module's datasource.
                "quarkus.datasource.lotes.active", "${zonacards.module.lotes.active}",
                "quarkus.datasource.giftcards.active", "${zonacards.module.giftcards.active}"));

    assertTrue(config.getValue("quarkus.datasource.lotes.active", Boolean.class));
    assertFalse(config.getValue("quarkus.datasource.giftcards.active", Boolean.class));
    assertFalse(config.getValue("zonacards.module.anything-else.active", Boolean.class));
  }

  @Test
  void theListToleratesSpacesAndComesFromAnExpression() {
    SmallRyeConfig config = config(Map.of("zonacards.modules", "${MODULES_OF_THIS_TEST}", "MODULES_OF_THIS_TEST", " giftcards , lotes ,"));

    assertTrue(config.getValue("zonacards.module.lotes.active", Boolean.class));
    assertTrue(config.getValue("zonacards.module.giftcards.active", Boolean.class));
  }

  @Test
  void onlyTheSwitchesAreAnswered() {
    SmallRyeConfig config = config(Map.of("zonacards.modules", "lotes"));

    assertEquals("lotes", config.getValue("zonacards.modules", String.class));
    assertFalse(config.getOptionalValue("zonacards.module.lotes", String.class).isPresent());
    assertFalse(config.getOptionalValue("zonacards.module..active", String.class).isPresent());
    assertFalse(config.getOptionalValue("zonacards.modulex.lotes.active", String.class).isPresent());
  }

  @Test
  void withoutTheVariableNothingIsSwitchedOnAndReadingTheListFails() {
    // What production looks like when a deployment forgets ZC_MODULES: the factory must not be
    // the one that fails (it also runs at build time), the read of the list must.
    SmallRyeConfig config = config(Map.of("zonacards.modules", "${ZC_MODULES_THAT_NOBODY_SET}"));

    assertFalse(config.getValue("zonacards.module.lotes.active", Boolean.class));
    NoSuchElementException failure =
        assertThrows(NoSuchElementException.class, () -> config.getValue("zonacards.modules", String.class));
    assertTrue(failure.getMessage().contains("ZC_MODULES_THAT_NOBODY_SET"), failure.getMessage());
  }
}
