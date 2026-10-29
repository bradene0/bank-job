package dev.bradenevans.bankjob.gameapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableResilientMethods
@EnableScheduling
public class GameApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(GameApiApplication.class, args);
  }
}
