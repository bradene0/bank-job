package dev.bradenevans.bankjob.gameapi.traces;

import dev.bradenevans.bankjob.gameapi.attempt.AttemptStore;
import dev.bradenevans.bankjob.gameapi.traces.TraceStore.SpanView;
import dev.bradenevans.bankjob.gameapi.traces.TraceStore.TraceStoreUnavailableException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /v1/traces/{traceId}}: the simplified trace of one of the caller's own attempts, for
 * the "Under the hood" panel. A trace id the caller's session did not produce is a 404, the same as
 * one that does not exist, so ids cannot be probed.
 */
@RestController
class TraceController {

  private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

  /** {@code complete} turns true once the consumers' spans have arrived (the UI stops polling). */
  record TraceView(String traceId, boolean complete, List<SpanView> spans) {}

  private final AttemptStore attempts;
  private final TraceStore traces;

  TraceController(AttemptStore attempts, TraceStore traces) {
    this.attempts = attempts;
    this.traces = traces;
  }

  @GetMapping("/v1/traces/{traceId}")
  TraceView trace(@AuthenticationPrincipal Jwt token, @PathVariable String traceId) {
    if (!TRACE_ID.matcher(traceId).matches()
        || !attempts.sessionOwnsTrace(UUID.fromString(token.getSubject()), traceId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such trace for this session.");
    }
    List<SpanView> spans = traces.spans(traceId);
    return new TraceView(traceId, complete(spans), spans);
  }

  /**
   * An attempt's trace ends with the leaderboard consuming its AttemptResolved event, the last
   * event the attempt produces.
   */
  static boolean complete(List<SpanView> spans) {
    return spans.stream()
        .anyMatch(
            s ->
                "consumer".equals(s.kind())
                    && "bankjob.outcomes".equals(s.attributes().get("messaging.source.name")));
  }

  @ExceptionHandler
  ResponseEntity<ProblemDetail> unavailable(TraceStoreUnavailableException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "5")
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Traces are unavailable right now."));
  }
}
