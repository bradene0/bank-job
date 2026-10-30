package dev.bradenevans.bankjob.gameapi.web;

import dev.bradenevans.bankjob.gameapi.accounts.AccountNotFoundException;
import dev.bradenevans.bankjob.gameapi.play.PlayExceptions.AttemptInProgressException;
import dev.bradenevans.bankjob.gameapi.play.PlayExceptions.InvalidAttemptException;
import dev.bradenevans.bankjob.gameapi.play.PlayExceptions.LivePlayUnavailableException;
import dev.bradenevans.bankjob.gameapi.play.PlayExceptions.NerveExhaustedException;
import dev.bradenevans.bankjob.gameapi.session.SessionRateLimitedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to RFC 9457 problem details. */
@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler
  ResponseEntity<ProblemDetail> rateLimited(SessionRateLimitedException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
        .body(problem);
  }

  @ExceptionHandler
  ProblemDetail notFound(AccountNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler
  ProblemDetail invalidAttempt(InvalidAttemptException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  /** Nerve does not come back with time, so there is no Retry-After. */
  @ExceptionHandler
  ProblemDetail nerveExhausted(NerveExhaustedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
  }

  @ExceptionHandler
  ProblemDetail inProgress(AttemptInProgressException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  /** The client switches to replay mode on this, so the game never dead-ends. */
  @ExceptionHandler
  ResponseEntity<ProblemDetail> livePlayUnavailable(LivePlayUnavailableException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    problem.setProperty("reason", e.reason());
    problem.setProperty("replay", true);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "60")
        .body(problem);
  }
}
