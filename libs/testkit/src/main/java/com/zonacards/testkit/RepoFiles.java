package com.zonacards.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates files of the repository from a test, whatever module the test runs in. */
public final class RepoFiles {

  private RepoFiles() {}

  public static Path root() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      if (Files.exists(dir.resolve("deploy/db/bootstrap-service.sql"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("repository root not found from " + Path.of("").toAbsolutePath());
  }

  public static String read(String relativePath) {
    try {
      return Files.readString(root().resolve(relativePath));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
