package dev.bradenevans.bankjob.gameapi.tools;

import dev.bradenevans.bankjob.gameapi.policy.ToolRequest;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns raw MCP arguments into a {@link ToolRequest}, strictly: unknown tools, missing or extra
 * arguments, wrong types and out-of-range values are all rejected. A rejection is still recorded as
 * a (denied) tool call.
 */
final class ToolArguments {

  static final class InvalidArgumentsException extends RuntimeException {
    InvalidArgumentsException(String message) {
      super(message);
    }
  }

  private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9-]{3,32}");
  private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 ()-]{6,19}");
  private static final BigDecimal MAX_USD = new BigDecimal("1000000");

  private ToolArguments() {}

  static ToolRequest parse(String tool, Map<String, Object> args) {
    Map<String, Object> a = args == null ? Map.of() : args;
    return switch (tool) {
      case "lookup_account" -> {
        expect(a, "account_hint");
        yield new ToolRequest.LookupAccount(text(a, "account_hint", 64));
      }
      case "get_balance" -> {
        expect(a, "account_number");
        yield new ToolRequest.GetBalance(account(a, "account_number"));
      }
      case "verify_identity" -> {
        expect(a, "account_number", "answer");
        yield new ToolRequest.VerifyIdentity(account(a, "account_number"), text(a, "answer", 64));
      }
      case "read_document" -> {
        expect(a, "document_id");
        yield new ToolRequest.ReadDocument(text(a, "document_id", 32));
      }
      case "update_contact_info" -> {
        expect(a, "account_number", "phone");
        String phone = text(a, "phone", 20);
        if (!PHONE.matcher(phone).matches()) {
          throw new InvalidArgumentsException("phone must be a phone number");
        }
        yield new ToolRequest.UpdateContactInfo(account(a, "account_number"), phone);
      }
      case "transfer_funds" -> {
        expect(a, "from_account", "to_account", "amount_usd", "idempotency_key");
        yield new ToolRequest.TransferFunds(
            account(a, "from_account"),
            account(a, "to_account"),
            cents(a.get("amount_usd")),
            text(a, "idempotency_key", 64));
      }
      case "flag_for_review" -> {
        expect(a, "reason");
        yield new ToolRequest.FlagForReview(text(a, "reason", 200));
      }
      default -> throw new InvalidArgumentsException("unknown tool " + tool);
    };
  }

  private static void expect(Map<String, Object> args, String... names) {
    Set<String> expected = Set.of(names);
    for (String key : args.keySet()) {
      if (!expected.contains(key)) {
        throw new InvalidArgumentsException("unexpected argument " + key);
      }
    }
    for (String name : names) {
      if (!args.containsKey(name)) {
        throw new InvalidArgumentsException("missing argument " + name);
      }
    }
  }

  private static String text(Map<String, Object> args, String name, int maxLength) {
    if (!(args.get(name) instanceof String value) || value.isBlank()) {
      throw new InvalidArgumentsException(name + " must be a non-empty string");
    }
    if (value.length() > maxLength) {
      throw new InvalidArgumentsException(name + " must be at most " + maxLength + " characters");
    }
    return value.strip();
  }

  private static String account(Map<String, Object> args, String name) {
    String value = text(args, name, 32);
    if (!ACCOUNT.matcher(value).matches()) {
      throw new InvalidArgumentsException(name + " must be an account number like BV-VAULT-01");
    }
    return value.toUpperCase(Locale.ROOT);
  }

  /** Dollars to exact cents; more than two decimal places is an error, not a rounding. */
  static long cents(Object amount) {
    BigDecimal usd =
        switch (amount) {
          case Integer i -> BigDecimal.valueOf(i);
          case Long l -> BigDecimal.valueOf(l);
          case BigDecimal d -> d;
          // JSON numbers may arrive as doubles; toString gives the shortest exact decimal form.
          case Double d when Double.isFinite(d) -> new BigDecimal(Double.toString(d));
          case null, default ->
              throw new InvalidArgumentsException("amount_usd must be a number of dollars");
        };
    if (usd.signum() <= 0 || usd.compareTo(MAX_USD) > 0) {
      throw new InvalidArgumentsException("amount_usd must be between 0.01 and 1000000");
    }
    try {
      return usd.movePointRight(2).setScale(0).longValueExact();
    } catch (ArithmeticException e) {
      throw new InvalidArgumentsException("amount_usd must have at most two decimal places");
    }
  }
}
