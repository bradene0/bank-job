package dev.bradenevans.bankjob.gameapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.ECKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeysTest {

  @TempDir Path dir;

  @Test
  void loadsAPemBundleWithPrivateAndPublicKeys() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair pair = generator.generateKeyPair();
    Path file = dir.resolve("jwt-es256.pem");
    Files.writeString(
        file,
        pem("PRIVATE KEY", pair.getPrivate().getEncoded())
            + pem("PUBLIC KEY", pair.getPublic().getEncoded()));

    ECKey key = SigningKeys.load(jwt(file.toString()), new SecretsProperties(false));

    assertThat(key.isPrivate()).isTrue();
    assertThat(key.toECPublicKey()).isEqualTo(pair.getPublic());
    assertThat(key.getKeyID()).isNotBlank();
  }

  @Test
  void refusesToStartWithoutAKeyUnlessEphemeralSecretsAreAllowed() {
    assertThatThrownBy(() -> SigningKeys.load(jwt(""), new SecretsProperties(false)))
        .hasMessageContaining("signing-key-path");
    assertThat(SigningKeys.load(jwt(""), new SecretsProperties(true)).isPrivate()).isTrue();
  }

  private static JwtProperties jwt(String path) {
    return new JwtProperties("iss", "aud", Duration.ofHours(1), path);
  }

  private static String pem(String type, byte[] der) {
    return "-----BEGIN "
        + type
        + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
        + "\n-----END "
        + type
        + "-----\n";
  }
}
