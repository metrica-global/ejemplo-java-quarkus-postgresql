package com.zonacards.server;

import jakarta.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Which modules this deployment serves: the modules the application contains, filtered by the
 * list in {@code zonacards.modules} ({@code ZC_MODULES}).
 */
@Singleton
public class ActiveModules {

  private final List<AppModule> known;
  private final Set<String> configured;

  public ActiveModules(List<AppModule> known, @ConfigProperty(name = "zonacards.modules") List<String> configured) {
    this.known = List.copyOf(known);
    this.configured = new LinkedHashSet<>(configured);
  }

  /**
   * A misspelled name would otherwise produce a healthy task that serves nothing, so it stops
   * the start instead.
   */
  public void validate() {
    Set<String> names = new LinkedHashSet<>();
    known.forEach(module -> names.add(module.name()));
    Set<String> unknown = new LinkedHashSet<>(configured);
    unknown.removeAll(names);
    if (!unknown.isEmpty()) {
      throw new IllegalStateException(
          "ZC_MODULES lists " + unknown + ", which this application does not contain. Its modules are " + names + ".");
    }
    if (configured.isEmpty()) {
      throw new IllegalStateException("ZC_MODULES is empty: a deployment must serve at least one of " + names + ".");
    }
  }

  public List<AppModule> all() {
    return known;
  }

  public List<AppModule> served() {
    return known.stream().filter(module -> configured.contains(module.name())).toList();
  }

  /** The module that owns the path, if this deployment does not serve it; null otherwise. */
  public AppModule ownerNotServed(String path) {
    String absolute = path.startsWith("/") ? path : "/" + path;
    for (AppModule module : known) {
      if (module.owns(absolute)) {
        return configured.contains(module.name()) ? null : module;
      }
    }
    return null;
  }
}
