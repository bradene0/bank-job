package dev.bradenevans.bankjob.gameapi.attempt;

import tools.jackson.databind.JsonNode;

/** One recorded tool call, in the order Penny made it. */
public record ToolCallRecord(
    int ordinal,
    String tool,
    JsonNode args,
    boolean allowed,
    String policyReason,
    JsonNode result,
    JsonNode review,
    int latencyMs) {}
