package com.zonacards.server;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Makes {@code zonacards.modules} the single switch of a deployment. For every name it answers
 * {@code zonacards.module.<name>.active} with true or false, and the application properties use
 * that to activate each module's datasource:
 *
 * <pre>quarkus.datasource.lotes.active=${zonacards.module.lotes.active}</pre>
 *
 * <p>Quarkus does not connect an inactive datasource, does not check its health and does not
 * need its credentials, so a deployment only has to be given the secrets of the modules it
 * serves.
 */
public class ModulesConfigSourceFactory implements ConfigSourceFactory {

  private static final String PREFIX = "zonacards.module.";
  private static final String SUFFIX = ".active";

  @Override
  public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
    return List.of(new ModuleSwitches(served(context)));
  }

  private static Set<String> served(ConfigSourceContext context) {
    String list;
    try {
      ConfigValue configured = context.getValue("zonacards.modules");
      list = configured == null || configured.getValue() == null ? "" : configured.getValue();
    } catch (NoSuchElementException e) {
      // ZC_MODULES was not given. Nothing is switched on here; the application then refuses to
      // start when it reads zonacards.modules, with an error that names the variable. This also
      // runs while the application is built, where the variable is legitimately absent.
      list = "";
    }
    return Arrays.stream(list.split(",")).map(String::trim).filter(name -> !name.isEmpty()).collect(Collectors.toSet());
  }

  private record ModuleSwitches(Set<String> served) implements ConfigSource {

    @Override
    public Set<String> getPropertyNames() {
      return Set.of();
    }

    @Override
    public String getValue(String name) {
      if (name.startsWith(PREFIX) && name.endsWith(SUFFIX) && name.length() > PREFIX.length() + SUFFIX.length()) {
        return String.valueOf(served.contains(name.substring(PREFIX.length(), name.length() - SUFFIX.length())));
      }
      return null;
    }

    @Override
    public String getName() {
      return "zonacards-module-switches";
    }

    @Override
    public int getOrdinal() {
      // Above application.properties (250), below environment variables (300).
      return 275;
    }
  }
}
