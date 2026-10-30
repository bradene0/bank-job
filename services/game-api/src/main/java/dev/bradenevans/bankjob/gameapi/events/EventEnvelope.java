package dev.bradenevans.bankjob.gameapi.events;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON every event is wrapped in: {@code event_id}, {@code event_type}, {@code schema_version},
 * {@code occurred_at}, then the payload fields. Trace context travels in Kafka headers, not here.
 */
public record EventEnvelope(
    UUID eventId, EventType type, int schemaVersion, Instant occurredAt, JsonNode body) {

  /** A message no retry can fix (not JSON, unknown type or version). It goes to the DLQ. */
  public static class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message) {
      super(message);
    }
  }

  public static ObjectNode wrap(
      JsonMapper json, UUID eventId, EventType type, Instant occurredAt, JsonNode payload) {
    ObjectNode envelope = json.createObjectNode();
    envelope.put("event_id", eventId.toString());
    envelope.put("event_type", type.wire());
    envelope.put("schema_version", EventType.SCHEMA_VERSION);
    envelope.put("occurred_at", occurredAt.toString());
    payload.properties().forEach(field -> envelope.set(field.getKey(), field.getValue()));
    return envelope;
  }

  public static EventEnvelope parse(JsonMapper json, String value) {
    JsonNode body;
    try {
      body = json.readTree(value == null ? "" : value);
    } catch (JacksonException e) {
      throw new MalformedEventException("Not JSON");
    }
    if (body == null || !body.isObject()) {
      throw new MalformedEventException("Not a JSON object");
    }
    EventType type =
        EventType.fromWire(body.path("event_type").asString(""))
            .orElseThrow(() -> new MalformedEventException("Unknown event_type"));
    int version = body.path("schema_version").asInt(0);
    if (version != EventType.SCHEMA_VERSION) {
      throw new MalformedEventException("Unsupported schema_version " + version);
    }
    try {
      return new EventEnvelope(
          UUID.fromString(body.path("event_id").asString("")),
          type,
          version,
          Instant.parse(body.path("occurred_at").asString("")),
          body);
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new MalformedEventException("Bad event_id or occurred_at");
    }
  }

  public String text(String field) {
    JsonNode node = body.get(field);
    if (node == null || !node.isString()) {
      throw new MalformedEventException(type.wire() + " is missing " + field);
    }
    return node.asString();
  }

  /** A UUID field, or null when it is absent or JSON null (e.g. a transfer with no attempt). */
  public UUID optionalUuid(String field) {
    JsonNode node = body.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    return uuid(field);
  }

  public UUID uuid(String field) {
    try {
      return UUID.fromString(text(field));
    } catch (IllegalArgumentException e) {
      throw new MalformedEventException(type.wire() + "." + field + " is not a UUID");
    }
  }

  /** A number field, or {@code fallback} when it is absent (an optional, later-added field). */
  public long optionalNumber(String field, long fallback) {
    JsonNode node = body.get(field);
    return node == null || node.isNull() ? fallback : number(field);
  }

  public long number(String field) {
    JsonNode node = body.get(field);
    if (node == null || !node.canConvertToExactIntegral()) {
      throw new MalformedEventException(type.wire() + " is missing " + field);
    }
    return node.asLong();
  }

  public Instant instant(String field) {
    try {
      return Instant.parse(text(field));
    } catch (DateTimeParseException e) {
      throw new MalformedEventException(type.wire() + "." + field + " is not a timestamp");
    }
  }
}
