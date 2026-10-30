package dev.bradenevans.bankjob.gameapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bradenevans.bankjob.gameapi.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** What the game in the browser (a different origin) needs from the API, and nothing more. */
@IntegrationTest
class CorsIT {

  private static final String GAME = "http://localhost:5173";

  @Autowired MockMvcTester mvc;

  @Test
  void theGameMayPollReadiness() {
    MvcTestResult result = mvc.get().uri("/healthz").header(HttpHeaders.ORIGIN, GAME).exchange();

    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo(GAME);
  }

  @Test
  void theGameMaySendAttemptsWithATraceparent() {
    MvcTestResult preflight =
        mvc.options()
            .uri("/v1/attempts")
            .header(HttpHeaders.ORIGIN, GAME)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(
                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                "authorization,content-type,traceparent")
            .exchange();

    assertThat(preflight).hasStatus(HttpStatus.OK);
    assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
        .containsIgnoringCase("traceparent");
  }

  @Test
  void otherOriginsGetNoCorsGrant() {
    MvcTestResult result =
        mvc.get().uri("/healthz").header(HttpHeaders.ORIGIN, "https://evil.example").exchange();

    assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
  }
}
