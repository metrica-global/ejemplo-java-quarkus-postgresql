package com.zonacards.testkit;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * A real PostgreSQL 18, shared by every test of a JVM. Schemas and roles are created with the
 * same script that production uses ({@code deploy/db/bootstrap-service.sql}), so the tests run
 * with the same privileges as the deployed service.
 */
public final class PostgresFixture {

  public static final String IMAGE = "postgres:18";
  public static final String DATABASE = "zonacards";
  private static final String ADMIN = "postgres";
  private static final String ADMIN_PASSWORD = "postgres";

  private static PostgresFixture shared;

  private final GenericContainer<?> container;
  private final Set<String> bootstrapped = ConcurrentHashMap.newKeySet();

  private PostgresFixture() {
    container =
        new GenericContainer<>(IMAGE)
            .withEnv("POSTGRES_PASSWORD", ADMIN_PASSWORD)
            .withEnv("POSTGRES_DB", DATABASE)
            .withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off")
            .withExposedPorts(5432)
            // The image starts the server twice: once to initialise, once for real.
            .waitingFor(
                Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                    .withStartupTimeout(Duration.ofSeconds(120)));
    container.start();
  }

  /** Started on first use and stopped by Testcontainers when the JVM exits. */
  public static synchronized PostgresFixture shared() {
    if (shared == null) {
      shared = new PostgresFixture();
    }
    return shared;
  }

  public String jdbcUrl() {
    return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + DATABASE;
  }

  /** Runs the production bootstrap script for one service. Idempotent. */
  public PostgresFixture bootstrapService(String service, int appConnectionLimit) {
    if (!bootstrapped.add(service)) {
      return this;
    }
    try {
      container.copyFileToContainer(
          Transferable.of(RepoFiles.read("deploy/db/bootstrap-service.sql")), "/tmp/bootstrap-service.sql");
      ExecResult result =
          container.execInContainer(
              "psql", "-v", "ON_ERROR_STOP=1", "-U", ADMIN, "-d", DATABASE,
              "-v", "svc=" + service,
              "-v", "owner_password=" + ownerPassword(service),
              "-v", "app_password=" + appPassword(service),
              "-v", "app_conn_limit=" + appConnectionLimit,
              "-f", "/tmp/bootstrap-service.sql");
      if (result.getExitCode() != 0) {
        throw new IllegalStateException("bootstrap of " + service + " failed: " + result.getStderr());
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return this;
  }

  public static String ownerUser(String service) {
    return service + "_owner";
  }

  public static String ownerPassword(String service) {
    return service + "-owner-secret";
  }

  public static String appUser(String service) {
    return service + "_app";
  }

  public static String appPassword(String service) {
    return service + "-app-secret";
  }

  public DataSource ownerDataSource(String service) {
    return dataSource(ownerUser(service), ownerPassword(service));
  }

  public DataSource appDataSource(String service) {
    return dataSource(appUser(service), appPassword(service));
  }

  public DataSource adminDataSource() {
    return dataSource(ADMIN, ADMIN_PASSWORD);
  }

  public DataSource dataSource(String user, String password) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(jdbcUrl());
    dataSource.setUser(user);
    dataSource.setPassword(password);
    return dataSource;
  }

  /** Runs statements as the owner of the service schema. For test setup only. */
  public void executeAsOwner(String service, String... statements) {
    try (Connection connection = ownerDataSource(service).getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
