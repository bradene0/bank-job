package dev.bradenevans.bankjob.gameapi.hello;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** M0 smoke endpoint proving the service is wired end to end. */
@RestController
class HelloController {

  record Hello(String service, String message) {}

  @GetMapping("/v1/hello")
  Hello hello() {
    return new Hello("game-api", "Penny is at the front desk.");
  }
}
