package dev.bradenevans.bankjob.gameapi.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guest token settings. {@code signingKeyPath} points at a PEM file holding the ES256 private key
 * (PKCS#8) followed by its public key; in production it is mounted from SSM.
 */
@ConfigurationProperties("bankjob.jwt")
public record JwtProperties(String issuer, String audience, Duration ttl, String signingKeyPath) {}
