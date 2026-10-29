package dev.bradenevans.bankjob.gameapi.session;

import dev.bradenevans.bankjob.gameapi.branch.BranchProvisioner;
import dev.bradenevans.bankjob.gameapi.content.GameContent;
import dev.bradenevans.bankjob.gameapi.security.JwtProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SessionService {

  record CreatedSession(
      UUID id, Instant createdAt, Instant expiresAt, Map<String, Integer> nerve) {}

  private final SessionRepository sessions;
  private final BranchProvisioner branch;
  private final GameContent content;
  private final JwtProperties jwt;
  private final Clock clock;

  SessionService(
      SessionRepository sessions,
      BranchProvisioner branch,
      GameContent content,
      JwtProperties jwt,
      Clock clock) {
    this.sessions = sessions;
    this.branch = branch;
    this.content = content;
    this.jwt = jwt;
    this.clock = clock;
  }

  /** Creates the session row and its private branch in one transaction. */
  @Transactional
  CreatedSession create(String ipHash) {
    // Second precision so the JWT's iat/exp and the stored timestamps agree exactly.
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
    Session session =
        sessions.save(new Session(now, now.plus(jwt.ttl()), ipHash, content.initialNerve()));
    branch.provision(session.id());
    return new CreatedSession(
        session.id(), session.createdAt(), session.expiresAt(), session.nerveRemaining());
  }
}
