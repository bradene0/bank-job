package dev.bradenevans.bankjob.gameapi.web;

import dev.bradenevans.bankjob.gameapi.accounts.AccountNotFoundException;
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
}
