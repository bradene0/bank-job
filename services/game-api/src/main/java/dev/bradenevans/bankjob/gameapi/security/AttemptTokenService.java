package dev.bradenevans.bankjob.gameapi.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues Penny's per-attempt token for the MCP server (contracts/agent-mcp.md). Its audience
 * differs from the guest token's, so neither token works on the other's endpoints. The subject is
 * the attempt id: every tool call is bound to one attempt and therefore one session and level.
 */
@Service
public class AttemptTokenService {

  public static final String AUDIENCE = "bankjob-mcp";
  public static final String SCOPE = "penny";
  static final Duration TTL = Duration.ofMinutes(2);

  private final JwtEncoder encoder;
  private final JwtProperties properties;
  private final Clock clock;

  AttemptTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
    this.encoder = encoder;
    this.properties = properties;
    this.clock = clock;
  }

  public String issue(UUID attemptId) {
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of(AUDIENCE))
            .subject(attemptId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(TTL))
            .id(UUID.randomUUID().toString())
            .claim("scope", SCOPE)
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
