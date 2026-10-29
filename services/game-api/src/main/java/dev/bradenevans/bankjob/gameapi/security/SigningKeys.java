package dev.bradenevans.bankjob.gameapi.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads (or, for local development only, generates) the ES256 key that signs guest tokens. */
final class SigningKeys {

  private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);
  private static final Pattern PEM_BLOCK =
      Pattern.compile("-----BEGIN ([A-Z ]+)-----([^-]+)-----END \\1-----");

  private SigningKeys() {}

  static ECKey load(JwtProperties jwt, SecretsProperties secrets) {
    String path = jwt.signingKeyPath();
    if (path != null && !path.isBlank()) {
      return fromPem(Path.of(path));
    }
    if (!secrets.allowEphemeral()) {
      throw new IllegalStateException(
          "bankjob.jwt.signing-key-path is not set and ephemeral secrets are not allowed");
    }
    log.warn("No JWT signing key configured; generated an ephemeral key (local use only)");
    try {
      return new ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException("Cannot generate an EC key", e);
    }
  }

  static ECKey fromPem(Path file) {
    try {
      String pem = Files.readString(file);
      ECPrivateKey privateKey = null;
      ECPublicKey publicKey = null;
      KeyFactory keys = KeyFactory.getInstance("EC");
      Matcher block = PEM_BLOCK.matcher(pem);
      while (block.find()) {
        byte[] der = Base64.getMimeDecoder().decode(block.group(2));
        switch (block.group(1)) {
          case "PRIVATE KEY" ->
              privateKey = (ECPrivateKey) keys.generatePrivate(new PKCS8EncodedKeySpec(der));
          case "PUBLIC KEY" ->
              publicKey = (ECPublicKey) keys.generatePublic(new X509EncodedKeySpec(der));
          default -> throw new IllegalStateException("Unexpected PEM block " + block.group(1));
        }
      }
      if (privateKey == null || publicKey == null) {
        throw new IllegalStateException(file + " must contain a PRIVATE KEY and a PUBLIC KEY");
      }
      return new ECKey.Builder(Curve.P_256, publicKey)
          .privateKey(privateKey)
          .keyIDFromThumbprint()
          .build();
    } catch (IOException | GeneralSecurityException | JOSEException e) {
      throw new IllegalStateException("Cannot load JWT signing key from " + file, e);
    }
  }
}
