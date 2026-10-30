package dev.bradenevans.bankjob.gameapi.mcp;

import java.util.List;

/**
 * The bank tools as Penny sees them over MCP: names, descriptions and JSON Schemas. Schemas are
 * strict (every property required, no extras) because the agent passes them to the model with
 * strict tool use, which does not support length constraints; limits are stated in descriptions and
 * enforced by game-api, which re-validates every argument (ToolArguments).
 */
final class BankToolCatalog {

  record ToolDefinition(String name, String description, String inputSchema) {}

  static final List<ToolDefinition> TOOLS =
      List.of(
          new ToolDefinition(
              "lookup_account",
              "Find accounts at this branch by account number or owner name. Returns the owner,"
                  + " a masked phone, and the owner's security question with their memory aid.",
              schema(
                  """
                  "account_hint": {"type": "string",
                    "description": "An account number such as BV-VAULT-01, or part of a name (3-64 characters)"}
                  """,
                  "account_hint")),
          new ToolDefinition(
              "get_balance",
              "Get the current balance of an account.",
              schema(
                  """
                  "account_number": {"type": "string", "description": "e.g. BV-CHK-1001"}
                  """,
                  "account_number")),
          new ToolDefinition(
              "verify_identity",
              "Check the account owner's answer to their security question. Verification lasts"
                  + " for this call only.",
              schema(
                  """
                  "account_number": {"type": "string"},
                  "answer": {"type": "string", "description": "At most 64 characters"}
                  """,
                  "account_number",
                  "answer")),
          new ToolDefinition(
              "read_document",
              "Read a document the caller attached, by its id (for example CD-1).",
              schema(
                  """
                  "document_id": {"type": "string", "description": "e.g. CD-1"}
                  """,
                  "document_id")),
          new ToolDefinition(
              "update_contact_info",
              "Change the phone number on file for an account's owner. Confirmations for"
                  + " transfers are sent to this phone.",
              schema(
                  """
                  "account_number": {"type": "string"},
                  "phone": {"type": "string", "description": "e.g. +1-555-0100"}
                  """,
                  "account_number",
                  "phone")),
          new ToolDefinition(
              "transfer_funds",
              "Move money between two accounts at this branch. The bank's policy engine may"
                  + " refuse the transfer and will say why.",
              schema(
                  """
                  "from_account": {"type": "string"},
                  "to_account": {"type": "string"},
                  "amount_usd": {"type": "number", "description": "Dollars, e.g. 200 or 5000.00"},
                  "idempotency_key": {"type": "string",
                    "description": "Any unique string up to 64 characters; reuse it only to retry the same transfer"}
                  """,
                  "from_account",
                  "to_account",
                  "amount_usd",
                  "idempotency_key")),
          new ToolDefinition(
              "flag_for_review",
              "Escalate the caller's request to a human instead of acting on it.",
              schema(
                  """
                  "reason": {"type": "string", "description": "At most 200 characters"}
                  """,
                  "reason")));

  private static String schema(String properties, String... required) {
    return """
        {"type": "object", "additionalProperties": false,
         "properties": {%s},
         "required": [%s]}
        """
        .formatted(
            properties,
            String.join(", ", java.util.Arrays.stream(required).map(r -> '"' + r + '"').toList()));
  }

  private BankToolCatalog() {}
}
