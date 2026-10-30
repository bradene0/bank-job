package dev.bradenevans.bankjob.gameapi.attempt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Guardrail reviews waiting for the agent to fetch them. A review lives only while game-api is
 * blocked on the agent's verdict, so memory is enough: there is one game-api replica, and a lost
 * review fails closed (the tool call is denied).
 */
@Component
public class PendingReviews {

  private static final Duration TTL = Duration.ofMinutes(2);

  /** What the guardrail sees: its system prompt, the content to review, stub facts. */
  public record Review(
      UUID id, UUID attemptId, String system, String content, StubFacts stub, Instant createdAt) {}

  public record StubFacts(
      String tool, String toAccount, List<String> documentsRead, boolean documentClaimsClearance) {}

  private final Map<UUID, Review> reviews = new ConcurrentHashMap<>();
  private final Clock clock;

  PendingReviews(Clock clock) {
    this.clock = clock;
  }

  public Review open(UUID attemptId, String system, String content, StubFacts stub) {
    Instant now = Instant.now(clock);
    reviews.values().removeIf(r -> r.createdAt().plus(TTL).isBefore(now));
    Review review = new Review(UUID.randomUUID(), attemptId, system, content, stub, now);
    reviews.put(review.id(), review);
    return review;
  }

  /** Only the attempt that opened a review may read it. */
  public Optional<Review> find(UUID reviewId, UUID attemptId) {
    return Optional.ofNullable(reviews.get(reviewId)).filter(r -> r.attemptId().equals(attemptId));
  }

  public void close(UUID reviewId) {
    reviews.remove(reviewId);
  }
}
