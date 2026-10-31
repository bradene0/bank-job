package dev.bradenevans.bankjob.gameapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A migration that has run against a database must never change, not even a comment: Flyway
 * checksums every applied migration and refuses to start when one differs, which in production
 * means game-api never comes up. Integration tests always start from an empty database, so they
 * cannot catch it. This test pins each migration's SHA-256; a new migration adds its line to
 * migration-checksums.txt.
 */
class MigrationChecksumsTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  private static final Path PINNED = Path.of("src/test/resources/migration-checksums.txt");

  @Test
  void appliedMigrationsNeverChange() throws IOException {
    Map<String, String> pinned;
    try (Stream<String> lines = Files.lines(PINNED)) {
      pinned =
          lines
              .filter(line -> !line.isBlank())
              .map(line -> line.split(" ", 2))
              .collect(Collectors.toMap(p -> p[0], p -> p[1], (a, b) -> a, TreeMap::new));
    }
    Map<String, String> actual = new TreeMap<>();
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
        actual.put(file.getFileName().toString(), sha256(Files.readAllBytes(file)));
      }
    }
    assertThat(actual)
        .as("migrations vs %s (add a line for a new migration; never edit an applied one)", PINNED)
        .isEqualTo(pinned);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
