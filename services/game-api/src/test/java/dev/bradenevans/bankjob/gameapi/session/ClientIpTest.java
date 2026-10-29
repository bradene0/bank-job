package dev.bradenevans.bankjob.gameapi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bradenevans.bankjob.gameapi.security.SecretsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {

  @Test
  void ignoresTheCloudflareHeaderUnlessTrusted() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.7");
    request.addHeader(ClientIp.CF_CONNECTING_IP, "203.0.113.9");

    assertThat(clientIp(false).resolve(request)).isEqualTo("10.0.0.7");
    assertThat(clientIp(true).resolve(request)).isEqualTo("203.0.113.9");
  }

  @Test
  void hashIsStableForOneSaltAndNeverTheRawAddress() {
    ClientIp ip = clientIp(false);
    String hash = ip.hash("203.0.113.9");

    assertThat(hash).isEqualTo(ip.hash("203.0.113.9")).hasSize(64).doesNotContain("203");
    assertThat(
            new ClientIp(properties(false, "other-salt"), new SecretsProperties(false))
                .hash("203.0.113.9"))
        .isNotEqualTo(hash);
  }

  @Test
  void refusesToStartWithoutASaltUnlessEphemeralSecretsAreAllowed() {
    assertThatThrownBy(() -> new ClientIp(properties(false, ""), new SecretsProperties(false)))
        .hasMessageContaining("ip-hash-salt");
    new ClientIp(properties(false, ""), new SecretsProperties(true)); // local dev: allowed
  }

  private static ClientIp clientIp(boolean trustCloudflare) {
    return new ClientIp(properties(trustCloudflare, "test-salt"), new SecretsProperties(false));
  }

  private static SessionProperties properties(boolean trustCloudflare, String salt) {
    return new SessionProperties(10, salt, trustCloudflare);
  }
}
