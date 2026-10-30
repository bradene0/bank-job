package dev.bradenevans.bankjob.gameapi.events;

import java.util.Arrays;
import java.util.Optional;

/** Every event game-api publishes, and the topic it goes to (docs/contracts/events/). */
public enum EventType {
  ATTEMPT_SUBMITTED("AttemptSubmitted", Topics.ATTEMPTS),
  TOOL_CALLED("ToolCalled", Topics.TOOL_CALLS),
  POLICY_DECIDED("PolicyDecided", Topics.TOOL_CALLS),
  TRANSFER_POSTED("TransferPosted", Topics.LEDGER),
  ATTEMPT_RESOLVED("AttemptResolved", Topics.OUTCOMES);

  /** Bumped when a payload changes incompatibly; consumers reject versions they do not know. */
  public static final int SCHEMA_VERSION = 1;

  private final String wire;
  private final String topic;

  EventType(String wire, String topic) {
    this.wire = wire;
    this.topic = topic;
  }

  public String wire() {
    return wire;
  }

  public String topic() {
    return topic;
  }

  public static Optional<EventType> fromWire(String wire) {
    return Arrays.stream(values()).filter(t -> t.wire.equals(wire)).findFirst();
  }
}
