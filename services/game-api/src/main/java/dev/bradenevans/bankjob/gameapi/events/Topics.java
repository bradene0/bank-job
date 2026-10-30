package dev.bradenevans.bankjob.gameapi.events;

import java.util.List;

/** Kafka topics. The broker never auto-creates topics; {@link KafkaConfig} declares these. */
public final class Topics {

  public static final String ATTEMPTS = "bankjob.attempts";
  public static final String TOOL_CALLS = "bankjob.tool-calls";
  public static final String LEDGER = "bankjob.ledger";
  public static final String OUTCOMES = "bankjob.outcomes";

  public static final List<String> ALL = List.of(ATTEMPTS, TOOL_CALLS, LEDGER, OUTCOMES);

  /** Where a message goes after its consumer gave up on it. */
  public static String deadLetter(String topic) {
    return topic + ".dlq";
  }

  private Topics() {}
}
