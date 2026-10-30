package dev.bradenevans.bankjob.gameapi.traces;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Reads traces back from Jaeger's query API (api/v3, OTLP JSON) and simplifies them for the "Under
 * the hood" panel. Only allowlisted attributes leave this class: span attributes can hold SQL and
 * other internals the player has no business seeing.
 */
@Component
class TraceStore {

  @ConfigurationProperties("bankjob.traces")
  record TraceProperties(String queryUrl, Duration timeout) {}

  record SpanView(
      String spanId,
      String parentSpanId,
      String service,
      String name,
      String kind,
      double startOffsetMs,
      double durationMs,
      boolean error,
      Map<String, Object> attributes) {}

  /** Jaeger could not be reached or answered with an error. */
  static class TraceStoreUnavailableException extends RuntimeException {
    TraceStoreUnavailableException(Throwable cause) {
      super("Trace store unavailable", cause);
    }
  }

  private static final List<String> ATTRIBUTE_PREFIXES =
      List.of(
          "bankjob.",
          "gen_ai.",
          "mcp.",
          "messaging.",
          "outbox.",
          "http.request.method",
          "http.route",
          "http.response.status_code",
          "db.system",
          "db.operation");

  private static final String[] KINDS = {
    "unspecified", "internal", "server", "client", "producer", "consumer"
  };

  private final RestClient jaeger;

  TraceStore(TraceProperties properties) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.timeout())
                .build());
    factory.setReadTimeout(properties.timeout());
    // Deliberately not observed: reading a trace should not add spans to traces.
    this.jaeger =
        RestClient.builder().baseUrl(properties.queryUrl()).requestFactory(factory).build();
  }

  /** The trace's spans in start order; empty while Jaeger has not received it yet. */
  List<SpanView> spans(String traceId) {
    JsonNode body;
    try {
      body = jaeger.get().uri("/api/v3/traces/{id}", traceId).retrieve().body(JsonNode.class);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        return List.of();
      }
      throw new TraceStoreUnavailableException(e);
    } catch (RestClientException e) {
      throw new TraceStoreUnavailableException(e);
    }
    return simplify(body);
  }

  static List<SpanView> simplify(JsonNode body) {
    record Raw(JsonNode span, String service, BigInteger start, BigInteger end) {}
    List<Raw> raw = new ArrayList<>();
    for (JsonNode resource : body.path("result").path("resourceSpans")) {
      String service = "unknown";
      for (JsonNode attribute : resource.path("resource").path("attributes")) {
        if ("service.name".equals(attribute.path("key").asString())) {
          service = attribute.path("value").path("stringValue").asString("unknown");
        }
      }
      for (JsonNode scope : resource.path("scopeSpans")) {
        for (JsonNode span : scope.path("spans")) {
          raw.add(
              new Raw(
                  span,
                  service,
                  new BigInteger(span.path("startTimeUnixNano").asString("0")),
                  new BigInteger(span.path("endTimeUnixNano").asString("0"))));
        }
      }
    }
    if (raw.isEmpty()) {
      return List.of();
    }
    BigInteger origin = raw.stream().map(Raw::start).min(Comparator.naturalOrder()).orElseThrow();
    return raw.stream()
        .sorted(Comparator.comparing(Raw::start))
        .map(
            r ->
                new SpanView(
                    r.span().path("spanId").asString(),
                    r.span().path("parentSpanId").asString(null),
                    r.service(),
                    r.span().path("name").asString(),
                    kind(r.span().path("kind").asInt(0)),
                    millis(r.start().subtract(origin)),
                    millis(r.end().subtract(r.start()).max(BigInteger.ZERO)),
                    r.span().path("status").path("code").asInt(0) == 2, // STATUS_CODE_ERROR
                    attributes(r.span().path("attributes"))))
        .toList();
  }

  private static String kind(int kind) {
    return kind >= 0 && kind < KINDS.length ? KINDS[kind] : "unspecified";
  }

  private static double millis(BigInteger nanos) {
    return Math.round(nanos.doubleValue() / 1_000) / 1_000.0; // microsecond precision
  }

  private static Map<String, Object> attributes(JsonNode attributes) {
    Map<String, Object> kept = new LinkedHashMap<>();
    for (JsonNode attribute : attributes) {
      String key = attribute.path("key").asString();
      if (ATTRIBUTE_PREFIXES.stream().noneMatch(key::startsWith)) {
        continue;
      }
      JsonNode value = attribute.path("value");
      if (value.has("stringValue")) {
        kept.put(key, value.get("stringValue").asString());
      } else if (value.has("intValue")) {
        kept.put(key, Long.parseLong(value.get("intValue").asString()));
      } else if (value.has("doubleValue")) {
        kept.put(key, value.get("doubleValue").asDouble());
      } else if (value.has("boolValue")) {
        kept.put(key, value.get("boolValue").asBoolean());
      }
    }
    return kept;
  }
}
