package dev.bradenevans.bankjob.gameapi.session;

import dev.bradenevans.bankjob.gameapi.security.SecretsProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Resolves the caller's IP and hashes it; raw IPs never reach the database or the logs. */
@Component
class ClientIp {

  private static final Logger log = LoggerFactory.getLogger(ClientIp.class);
  static final String CF_CONNECTING_IP = "CF-Connecting-IP";

  private final boolean trustCloudflareHeader;
  private final SecretKeySpec hmacKey;

  ClientIp(SessionProperties sessions, SecretsProperties secrets) {
    this.trustCloudflareHeader = sessions.trustCfConnectingIp();
    byte[] salt;
    if (sessions.ipHashSalt() != null && !sessions.ipHashSalt().isBlank()) {
      salt = sessions.ipHashSalt().getBytes(StandardCharsets.UTF_8);
    } else if (secrets.allowEphemeral()) {
      log.warn("No IP hash salt configured; generated an ephemeral one (local use only)");
      salt = new byte[32];
      new SecureRandom().nextBytes(salt);
    } else {
      throw new IllegalStateException(
          "bankjob.sessions.ip-hash-salt is not set and ephemeral secrets are not allowed");
    }
    this.hmacKey = new SecretKeySpec(salt, "HmacSHA256");
  }

  String resolve(HttpServletRequest request) {
    if (trustCloudflareHeader) {
      String header = request.getHeader(CF_CONNECTING_IP);
      if (header != null && !header.isBlank()) {
        return header.strip();
      }
    }
    return request.getRemoteAddr();
  }

  String hash(String ip) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(hmacKey);
      return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is always available", e);
    }
  }
}
