package dev.bradenevans.bankjob.gameapi.session;

import dev.bradenevans.bankjob.gameapi.security.GuestTokenService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SessionController {

  record SessionResponse(
      UUID sessionId,
      String token,
      String tokenType,
      Instant expiresAt,
      Map<String, Integer> nerve) {}

  private final SessionService sessions;
  private final SessionCreationLimiter limiter;
  private final ClientIp clientIp;
  private final GuestTokenService tokens;

  SessionController(
      SessionService sessions,
      SessionCreationLimiter limiter,
      ClientIp clientIp,
      GuestTokenService tokens) {
    this.sessions = sessions;
    this.limiter = limiter;
    this.clientIp = clientIp;
    this.tokens = tokens;
  }

  /** Anonymous: no body, no login. The token is issued only after the session commits. */
  @PostMapping("/v1/sessions")
  @ResponseStatus(HttpStatus.CREATED)
  SessionResponse create(HttpServletRequest request) {
    String ipHash = clientIp.hash(clientIp.resolve(request));
    if (!limiter.tryAcquire(ipHash)) {
      throw new SessionRateLimitedException(limiter.secondsUntilReset());
    }
    SessionService.CreatedSession session = sessions.create(ipHash);
    String token = tokens.issue(session.id(), session.createdAt(), session.expiresAt());
    return new SessionResponse(session.id(), token, "Bearer", session.expiresAt(), session.nerve());
  }
}
