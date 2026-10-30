package dev.bradenevans.bankjob.gameapi.traces;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bradenevans.bankjob.gameapi.traces.TraceStore.SpanView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Jaeger's api/v3 answer (OTLP JSON, as Jaeger 2.22 returns it) to the panel's span list. */
class TraceStoreTest {

  private static final String JAEGER_RESPONSE =
      """
      {"result": {"resourceSpans": [
        {"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "agent"}}]},
         "scopeSpans": [{"scope": {}, "spans": [
           {"traceId": "0af7651916cd43dd8448eb211c80319c", "spanId": "c7ad6b7169203332",
            "parentSpanId": "b7ad6b7169203331", "name": "MCP send tools/call transfer_funds",
            "kind": 3, "startTimeUnixNano": "1793322648813735000",
            "endTimeUnixNano": "1793322648814985500", "status": {"code": 2},
            "attributes": [
              {"key": "mcp.method.name", "value": {"stringValue": "tools/call"}},
              {"key": "db.statement", "value": {"stringValue": "SELECT secret"}}]}]}]},
        {"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "game-api"}}]},
         "scopeSpans": [{"scope": {}, "spans": [
           {"traceId": "0af7651916cd43dd8448eb211c80319c", "spanId": "b7ad6b7169203331",
            "name": "http post /v1/attempts", "kind": 2,
            "startTimeUnixNano": "1793322648812735000",
            "endTimeUnixNano": "1793322648817735000", "status": {},
            "attributes": [
              {"key": "bankjob.level", "value": {"intValue": "5"}},
              {"key": "bankjob.cached", "value": {"boolValue": false}},
              {"key": "jdbc.query[0]", "value": {"stringValue": "INSERT ..."}}]}]}]}]}}
      """;

  @Test
  void spansAreOrderedTimedAndStrippedToAllowlistedAttributes() {
    List<SpanView> spans =
        TraceStore.simplify(JsonMapper.builder().build().readTree(JAEGER_RESPONSE));

    assertThat(spans).extracting(SpanView::service).containsExactly("game-api", "agent");
    SpanView root = spans.get(0);
    assertThat(root.parentSpanId()).isNull();
    assertThat(root.kind()).isEqualTo("server");
    assertThat(root.startOffsetMs()).isZero();
    assertThat(root.durationMs()).isEqualTo(5.0);
    assertThat(root.attributes()).isEqualTo(Map.of("bankjob.level", 5L, "bankjob.cached", false));

    SpanView child = spans.get(1);
    assertThat(child.parentSpanId()).isEqualTo("b7ad6b7169203331");
    assertThat(child.kind()).isEqualTo("client");
    assertThat(child.startOffsetMs()).isEqualTo(1.0);
    assertThat(child.durationMs()).isEqualTo(1.251);
    assertThat(child.error()).isTrue();
    assertThat(child.attributes()).containsOnlyKeys("mcp.method.name");
  }

  @Test
  void anEmptyAnswerIsNoSpans() {
    assertThat(TraceStore.simplify(JsonMapper.builder().build().readTree("{}"))).isEmpty();
  }
}
