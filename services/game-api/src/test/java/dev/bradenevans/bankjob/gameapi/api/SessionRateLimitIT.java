package dev.bradenevans.bankjob.gameapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bradenevans.bankjob.gameapi.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
@TestPropertySource(properties = "bankjob.sessions.max-per-ip-per-hour=2")
class SessionRateLimitIT {

  @Autowired MockMvcTester mvc;

  @Test
  void tooManySessionsFromOneAddressGet429WithRetryAfter() {
    assertThat(mvc.post().uri("/v1/sessions").exchange()).hasStatus(HttpStatus.CREATED);
    assertThat(mvc.post().uri("/v1/sessions").exchange()).hasStatus(HttpStatus.CREATED);

    MvcTestResult limited = mvc.post().uri("/v1/sessions").exchange();

    assertThat(limited)
        .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        .hasContentType("application/problem+json");
    assertThat(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
  }
}
