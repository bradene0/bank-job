package dev.bradenevans.bankjob.gameapi.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Move {@code amountCents} from one account to another within a session. With an idempotency key,
 * retrying the same command returns the original result instead of moving money again.
 */
public record TransferCommand(
    UUID sessionId,
    UUID fromAccountId,
    UUID toAccountId,
    long amountCents,
    String description,
    AttemptRef attempt,
    String idempotencyKey) {

  public TransferCommand {
    if (sessionId == null || fromAccountId == null || toAccountId == null) {
      throw new IllegalArgumentException("sessionId, fromAccountId and toAccountId are required");
    }
    if (fromAccountId.equals(toAccountId)) {
      throw new IllegalArgumentException("Cannot transfer an account to itself");
    }
    if (amountCents <= 0) {
      throw new IllegalArgumentException("amountCents must be positive");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description is required");
    }
    if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 200)) {
      throw new IllegalArgumentException("idempotencyKey must be 1-200 characters");
    }
  }

  /** Fingerprint of everything that defines the request, so a reused key can be detected. */
  String requestHash() {
    String canonical =
        String.join(
            "|",
            sessionId.toString(),
            fromAccountId.toString(),
            toAccountId.toString(),
            Long.toString(amountCents),
            description);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
