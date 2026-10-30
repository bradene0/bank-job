package dev.bradenevans.bankjob.gameapi.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bradenevans.bankjob.gameapi.policy.ToolRequest;
import dev.bradenevans.bankjob.gameapi.tools.ToolArguments.InvalidArgumentsException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolArgumentsTest {

  @Test
  void parsesATransferIntoExactCents() {
    ToolRequest request =
        ToolArguments.parse(
            "transfer_funds",
            Map.of(
                "from_account", "bv-vault-01",
                "to_account", "BV-MULE-01",
                "amount_usd", 5000.5,
                "idempotency_key", "k1"));

    assertThat(request)
        .isEqualTo(new ToolRequest.TransferFunds("BV-VAULT-01", "BV-MULE-01", 500_050, "k1"));
  }

  @Test
  void dollarAmountsConvertWithoutFloatingPointDrift() {
    assertThat(ToolArguments.cents(0.1)).isEqualTo(10);
    assertThat(ToolArguments.cents(200)).isEqualTo(20_000);
    assertThat(ToolArguments.cents(1_000_000L)).isEqualTo(100_000_000);
    assertThat(ToolArguments.cents(new BigDecimal("19.99"))).isEqualTo(1_999);
  }

  @Test
  void rejectsAmountsThatAreNotWholeCentsOrOutOfRange() {
    for (Object bad : new Object[] {0.001, 0, -5, 1_000_000.01, Double.NaN, "200", null}) {
      assertThatThrownBy(() -> ToolArguments.cents(bad))
          .as("amount %s", bad)
          .isInstanceOf(InvalidArgumentsException.class);
    }
  }

  @Test
  void rejectsMissingAndExtraArguments() {
    assertThatThrownBy(() -> ToolArguments.parse("get_balance", Map.of()))
        .hasMessageContaining("missing argument account_number");
    assertThatThrownBy(
            () ->
                ToolArguments.parse(
                    "get_balance", Map.of("account_number", "BV-VAULT-01", "note", "hi")))
        .hasMessageContaining("unexpected argument note");
  }

  @Test
  void rejectsWrongTypesAndOversizedText() {
    Map<String, Object> args = new HashMap<>();
    args.put("reason", 42);
    assertThatThrownBy(() -> ToolArguments.parse("flag_for_review", args))
        .hasMessageContaining("reason must be a non-empty string");
    assertThatThrownBy(
            () -> ToolArguments.parse("flag_for_review", Map.of("reason", "x".repeat(201))))
        .hasMessageContaining("at most 200");
    assertThatThrownBy(
            () -> ToolArguments.parse("get_balance", Map.of("account_number", "BV VAULT; DROP")))
        .hasMessageContaining("account number");
    assertThatThrownBy(
            () ->
                ToolArguments.parse(
                    "update_contact_info",
                    Map.of("account_number", "BV-VAULT-01", "phone", "call me maybe")))
        .hasMessageContaining("phone");
  }

  @Test
  void rejectsUnknownTools() {
    assertThatThrownBy(() -> ToolArguments.parse("open_vault_door", Map.of()))
        .hasMessageContaining("unknown tool");
  }
}
