package dev.bradenevans.bankjob.gameapi.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerRequestValidationTest {

  static final UUID SESSION = UUID.randomUUID();
  static final UUID A = UUID.randomUUID();
  static final UUID B = UUID.randomUUID();

  @Test
  void postingRequestMustBalance() {
    assertThatThrownBy(() -> new PostingRequest("x", null, List.of(new Leg(A, -5), new Leg(B, 4))))
        .hasMessageContaining("sum to zero");
  }

  @Test
  void postingRequestNeedsTwoDistinctAccounts() {
    assertThatThrownBy(() -> new PostingRequest("x", null, List.of(new Leg(A, 0))))
        .hasMessageContaining("zero");
    assertThatThrownBy(() -> new PostingRequest("x", null, List.of(new Leg(A, 5), new Leg(A, -5))))
        .hasMessageContaining("appears twice");
  }

  @Test
  void transferRejectsNonPositiveAmountsAndSelfTransfers() {
    assertThatThrownBy(() -> command(A, B, 0, null)).hasMessageContaining("positive");
    assertThatThrownBy(() -> command(A, A, 5, null)).hasMessageContaining("itself");
    assertThatThrownBy(() -> command(A, B, 5, " ")).hasMessageContaining("idempotencyKey");
  }

  @Test
  void requestHashIdentifiesTheRequestNotTheKey() {
    assertThat(command(A, B, 5, "k1").requestHash())
        .isEqualTo(command(A, B, 5, "k2").requestHash());
    assertThat(command(A, B, 5, "k1").requestHash())
        .isNotEqualTo(command(A, B, 6, "k1").requestHash());
    assertThat(command(A, B, 5, "k1").requestHash())
        .isNotEqualTo(command(B, A, 5, "k1").requestHash());
  }

  private static TransferCommand command(UUID from, UUID to, long cents, String key) {
    return new TransferCommand(SESSION, from, to, cents, "test", null, key);
  }
}
