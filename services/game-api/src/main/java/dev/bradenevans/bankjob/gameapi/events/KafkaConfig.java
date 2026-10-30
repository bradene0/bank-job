package dev.bradenevans.bankjob.gameapi.events;

import dev.bradenevans.bankjob.gameapi.events.EventEnvelope.MalformedEventException;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/** Topics, and what a consumer does with a message it cannot process. */
@Configuration
class KafkaConfig {

  @ConfigurationProperties("bankjob.kafka")
  record KafkaProperties(int partitions, int consumerRetries) {}

  /**
   * Every topic and its dead-letter topic. The broker never auto-creates topics; the outbox relay
   * creates these before its first send. Single broker, so replication factor 1. Keys keep one
   * session's or account's events in order.
   */
  @Bean
  KafkaAdmin.NewTopics topics(KafkaProperties kafka) {
    List<NewTopic> topics = new ArrayList<>();
    for (String topic : Topics.ALL) {
      topics.add(TopicBuilder.name(topic).partitions(kafka.partitions()).replicas(1).build());
      topics.add(TopicBuilder.name(Topics.deadLetter(topic)).partitions(1).replicas(1).build());
    }
    return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
  }

  /**
   * Transient failures (the database is briefly unavailable) are retried with backoff; then, or
   * immediately for a malformed message, the record goes to {@code <topic>.dlq} with the failure in
   * its headers, and the consumer moves on instead of blocking its partition.
   */
  @Bean
  CommonErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> template, KafkaProperties kafka) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, failure) -> new TopicPartition(Topics.deadLetter(record.topic()), -1));
    var backOff = new ExponentialBackOffWithMaxRetries(kafka.consumerRetries());
    backOff.setInitialInterval(200);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(2_000);
    var handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(MalformedEventException.class);
    return handler;
  }
}
