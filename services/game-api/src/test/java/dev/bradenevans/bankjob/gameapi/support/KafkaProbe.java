package dev.bradenevans.bankjob.gameapi.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Looks at the broker from the outside: what is on a topic, and whether consumers caught up. */
public final class KafkaProbe implements AutoCloseable {

  private final String bootstrapServers;
  private final Admin admin;

  public KafkaProbe(String bootstrapServers) {
    this.bootstrapServers = bootstrapServers;
    Properties config = new Properties();
    config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    this.admin = Admin.create(config);
  }

  /** Every record on the topics right now, from the beginning, without joining a group. */
  public List<ConsumerRecord<String, String>> readAll(Collection<String> topics) {
    Properties config = new Properties();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    try (var consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      List<TopicPartition> partitions =
          topics.stream()
              .flatMap(
                  t ->
                      consumer.partitionsFor(t).stream()
                          .map(p -> new TopicPartition(t, p.partition())))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      List<ConsumerRecord<String, String>> records = new ArrayList<>();
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("Timed out reading " + topics);
        }
        consumer.poll(Duration.ofMillis(200)).forEach(records::add);
      }
      return records;
    }
  }

  public void send(String topic, String key, String value) {
    Properties config = new Properties();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    try (var producer =
        new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
      producer.send(new ProducerRecord<>(topic, key, value)).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Waits until each consumer group has committed offsets up to the end of every partition of its
   * topics, i.e. it has processed everything published so far.
   */
  public void awaitCaughtUp(Map<String, List<String>> topicsByGroup, Duration timeout) {
    await()
        .atMost(timeout)
        .pollInterval(Duration.ofMillis(200))
        .until(() -> lag(topicsByGroup) == 0);
  }

  private long lag(Map<String, List<String>> topicsByGroup) throws Exception {
    long lag = 0;
    for (var entry : topicsByGroup.entrySet()) {
      Map<TopicPartition, OffsetAndMetadata> committed =
          admin.listConsumerGroupOffsets(entry.getKey()).partitionsToOffsetAndMetadata().get();
      List<TopicPartition> partitions = new ArrayList<>();
      for (var description :
          admin.describeTopics(entry.getValue()).allTopicNames().get().values()) {
        description
            .partitions()
            .forEach(p -> partitions.add(new TopicPartition(description.name(), p.partition())));
      }
      var latest =
          admin
              .listOffsets(
                  partitions.stream().collect(Collectors.toMap(p -> p, p -> OffsetSpec.latest())))
              .all()
              .get();
      for (TopicPartition partition : partitions) {
        OffsetAndMetadata offset = committed.get(partition);
        lag += latest.get(partition).offset() - (offset == null ? 0 : offset.offset());
      }
    }
    return lag;
  }

  @Override
  public void close() {
    admin.close();
  }
}
