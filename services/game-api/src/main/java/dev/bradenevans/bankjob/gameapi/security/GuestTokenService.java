package dev.bradenevans.bankjob.gameapi.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues the anonymous player token. The subject is the session id; nothing else is personal. */
@Service
public class GuestTokenService {

  public static final String PLAYER_SCOPE = "player";

  private final JwtEncoder encoder;
  private final JwtProperties properties;

  GuestTokenService(JwtEncoder encoder, JwtProperties properties) {
    this.encoder = encoder;
    this.properties = properties;
  }

  public String issue(UUID sessionId, Instant issuedAt, Instant expiresAt) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(java.util.List.of(properties.audience()))
            .subject(sessionId.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .claim("scope", PLAYER_SCOPE)
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
