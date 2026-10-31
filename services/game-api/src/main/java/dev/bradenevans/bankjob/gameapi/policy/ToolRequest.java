package dev.bradenevans.bankjob.gameapi.policy;

/** A parsed, validated bank tool call (contracts/agent-mcp.md). */
public sealed interface ToolRequest {

  String tool();

  /** Calls that move money or change an account; the guardrail reviews these at Level 5. */
  default boolean sensitive() {
    return this instanceof TransferFunds || this instanceof UpdateContactInfo;
  }

  record LookupAccount(String accountHint) implements ToolRequest {
    public String tool() {
      return "lookup_account";
    }
  }

  record GetBalance(String accountNumber) implements ToolRequest {
    public String tool() {
      return "get_balance";
    }
  }

  record VerifyIdentity(String accountNumber, String answer) implements ToolRequest {
    public String tool() {
      return "verify_identity";
    }
  }

  record ReadDocument(String documentId) implements ToolRequest {
    public String tool() {
      return "read_document";
    }
  }

  record UpdateContactInfo(String accountNumber, String phone) implements ToolRequest {
    public String tool() {
      return "update_contact_info";
    }
  }

  record TransferFunds(
      String fromAccount, String toAccount, long amountCents, String idempotencyKey)
      implements ToolRequest {
    public String tool() {
      return "transfer_funds";
    }
  }

  record FlagForReview(String reason) implements ToolRequest {
    public String tool() {
      return "flag_for_review";
    }
  }
}
