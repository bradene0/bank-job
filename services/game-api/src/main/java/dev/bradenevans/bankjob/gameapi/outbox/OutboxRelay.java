package dev.bradenevans.bankjob.gameapi.outbox;

import dev.bradenevans.bankjob.gameapi.events.EventEnvelope;
import dev.bradenevans.bankjob.gameapi.events.EventType;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends committed outbox rows to Kafka and marks them published (ADR-0002).
 *
 * <p>Delivery is at least once: a crash between the broker's ack and the {@code published_at}
 * update sends the row again, and consumers drop the duplicate by event id. Rows are selected by
 * {@code published_at IS NULL} rather than by a position, so a transaction that commits late (its
 * rows carry an earlier timestamp than rows already sent) is still picked up on the next pass.
 *
 * <p>One relay thread per process. {@code FOR UPDATE SKIP LOCKED} keeps a second replica from
 * sending the same rows, should game-api ever run more than one.
 */
@Component
class OutboxRelay implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

  @ConfigurationProperties("bankjob.outbox")
  record OutboxProperties(
      int batchSize, Duration pollInterval, Duration sendTimeout, Duration maxBackoff) {}

  private record Row(
      UUID id,
      String topic,
      String key,
      EventType type,
      String payload,
      String traceContext,
      Instant createdAt) {}

  record Pass(int published, int failed) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final KafkaTemplate<String, String> kafka;
  private final KafkaAdmin admin;
  private final Tracer tracer;
  private final Propagator propagator;
  private final JsonMapper json;
  private final OutboxProperties properties;
  private final Semaphore wakeUps = new Semaphore(0);
  private volatile boolean running;
  // Topics are created here, not at startup, so game-api never waits for the broker to boot.
  private volatile boolean topicsReady;
  private Thread worker;

  OutboxRelay(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      KafkaTemplate<String, String> kafka,
      KafkaAdmin admin,
      Tracer tracer,
      Propagator propagator,
      JsonMapper json,
      OutboxProperties properties) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.kafka = kafka;
    this.admin = admin;
    this.tracer = tracer;
    this.propagator = propagator;
    this.json = json;
    this.properties = properties;
  }

  /** Called after a transaction that wrote outbox rows commits. */
  void wakeUp() {
    wakeUps.release();
  }

  @Override
  public void start() {
    running = true;
    worker = Thread.ofPlatform().name("outbox-relay").daemon().start(this::loop);
  }

  @Override
  public void stop() {
    running = false;
    worker.interrupt();
    try {
      worker.join(properties.sendTimeout().toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void loop() {
    int failures = 0;
    while (running) {
      try {
        Pass pass = relayOnce();
        if (pass.failed() > 0) {
          failures++;
          log.warn("Outbox relay: {} event(s) not delivered; will retry", pass.failed());
          Thread.sleep(backoff(failures));
          continue;
        }
        failures = 0;
        if (pass.published() < properties.batchSize()) {
          // Caught up: sleep until a commit wakes us or the poll interval passes.
          if (wakeUps.tryAcquire(properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
            wakeUps.drainPermits();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // The database or the broker is unavailable; the rows wait in the table meanwhile.
        failures++;
        topicsReady = false;
        log.warn("Outbox relay failed: {}", e.toString());
        try {
          Thread.sleep(backoff(failures));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private long backoff(int failures) {
    long millis = properties.pollInterval().toMillis() << Math.min(failures, 10);
    return Math.min(millis, properties.maxBackoff().toMillis());
  }

  /** Relays one batch. Visible to tests, which drive it directly. */
  Pass relayOnce() {
    if (!topicsReady) {
      // Idempotent: creates missing topics (and their DLQs), leaves existing ones alone.
      topicsReady = admin.initialize();
      if (!topicsReady) {
        throw new IllegalStateException("Kafka topics could not be created yet");
      }
    }
    return transactions.execute(
        status -> {
          List<Row> rows = unpublished();
          if (rows.isEmpty()) {
            return new Pass(0, 0);
          }
          List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
          for (Row row : rows) {
            CompletableFuture<SendResult<String, String>> send = send(row);
            sends.add(send);
            if (send.isCompletedExceptionally()) {
              // The broker is unreachable (each further send would block for max.block.ms
              // while this transaction holds its row locks); retry the rest after a backoff.
              break;
            }
          }
          List<UUID> published = new ArrayList<>();
          long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
          for (int i = 0; i < sends.size(); i++) {
            if (acked(sends.get(i), deadline)) {
              published.add(rows.get(i).id());
            }
          }
          if (!published.isEmpty()) {
            jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id IN (:ids)")
                .param("ids", published)
                .update();
          }
          return new Pass(published.size(), rows.size() - published.size());
        });
  }

  private List<Row> unpublished() {
    return jdbc.sql(
            """
            SELECT id, topic, message_key, event_type, payload::text AS payload,
                   trace_context::text AS trace_context, created_at
            FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
        .param("limit", properties.batchSize())
        .query(
            (rs, n) ->
                new Row(
                    rs.getObject("id", UUID.class),
                    rs.getString("topic"),
                    rs.getString("message_key"),
                    EventType.fromWire(rs.getString("event_type")).orElseThrow(),
                    rs.getString("payload"),
                    rs.getString("trace_context"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /**
   * Publishes one row inside a span that continues the trace of the request that wrote it. The
   * Kafka producer span nests under it and carries the trace on to the consumers in its headers.
   */
  private CompletableFuture<SendResult<String, String>> send(Row row) {
    Span span =
        parent(row)
            .name("outbox relay " + row.type().wire())
            .tag("outbox.event_type", row.type().wire())
            .tag("outbox.lag_ms", Duration.between(row.createdAt(), Instant.now()).toMillis())
            .start();
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      String value =
          json.writeValueAsString(
              EventEnvelope.wrap(
                  json, row.id(), row.type(), row.createdAt(), json.readTree(row.payload())));
      ProducerRecord<String, String> record =
          new ProducerRecord<>(row.topic(), null, row.key(), value);
      record.headers().add("event_id", row.id().toString().getBytes(StandardCharsets.UTF_8));
      record.headers().add("event_type", row.type().wire().getBytes(StandardCharsets.UTF_8));
      return kafka
          .send(record)
          .whenComplete(
              (result, failure) -> {
                if (failure != null) {
                  span.error(failure);
                }
                span.end();
              });
    } catch (RuntimeException e) {
      span.error(e);
      span.end();
      return CompletableFuture.failedFuture(e);
    }
  }

  private Span.Builder parent(Row row) {
    if (row.traceContext() == null) {
      return tracer.spanBuilder().setNoParent();
    }
    Map<String, String> headers = json.readValue(row.traceContext(), HEADERS);
    return propagator.extract(headers, Map::get);
  }

  private static boolean acked(CompletableFuture<?> send, long deadlineNanos) {
    try {
      send.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
      return true;
    } catch (ExecutionException | TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
