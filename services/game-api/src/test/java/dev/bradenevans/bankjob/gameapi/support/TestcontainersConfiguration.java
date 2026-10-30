package dev.bradenevans.bankjob.gameapi.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL and a real Kafka (KRaft, single broker), the same versions as production, wired
 * in via @ServiceConnection.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:18.6-alpine");
  public static final DockerImageName KAFKA = DockerImageName.parse("apache/kafka:4.4.0");

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer(POSTGRES);
  }

  @Bean
  @ServiceConnection
  KafkaContainer kafka() {
    return new KafkaContainer(KAFKA);
  }
}
