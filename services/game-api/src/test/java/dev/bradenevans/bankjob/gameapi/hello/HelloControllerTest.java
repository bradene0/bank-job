package dev.bradenevans.bankjob.gameapi.hello;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(HelloController.class)
class HelloControllerTest {

  @Autowired MockMvcTester mvc;

  @Test
  void helloIdentifiesTheService() {
    assertThat(mvc.get().uri("/v1/hello"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.service")
        .isEqualTo("game-api");
  }
}
