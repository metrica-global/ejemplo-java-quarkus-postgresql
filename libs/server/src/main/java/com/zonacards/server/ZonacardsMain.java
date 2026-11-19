package com.zonacards.server;

import com.zonacards.logx.Logx;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Entry point of every service. The same image runs in two modes:
 *
 * <ul>
 *   <li>{@code serve} (default): the API, the consumers and the relay, with the runtime database
 *       role, which cannot change the schema;
 *   <li>{@code migrate} ({@code ZC_MODE=migrate}): applies the Flyway migrations with the owner
 *       role and exits. The deploy pipeline runs it as a one-off task before updating the
 *       service, so the long-running tasks never hold credentials that can alter tables.
 * </ul>
 */
@QuarkusMain
public class ZonacardsMain {

  private static final Logx LOG = Logx.get(ZonacardsMain.class);

  public static void main(String... args) {
    boolean profileGiven = System.getProperty("quarkus.profile") != null || System.getenv("QUARKUS_PROFILE") != null;
    if ("migrate".equalsIgnoreCase(System.getenv("ZC_MODE")) && !profileGiven) {
      System.setProperty("quarkus.profile", "prod,migrate");
    }
    Quarkus.run(App.class, args);
  }

  public static class App implements QuarkusApplication {

    @ConfigProperty(name = "zonacards.mode", defaultValue = "serve")
    String mode;

    @Override
    public int run(String... args) {
      if ("migrate".equalsIgnoreCase(mode)) {
        LOG.info("migrations applied; exiting");
        return 0;
      }
      Quarkus.waitForExit();
      return 0;
    }
  }
}
