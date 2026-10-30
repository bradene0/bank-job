package dev.bradenevans.bankjob.gameapi.traces;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** Keeps traces about attempts: noise that would start a trace of its own is not recorded. */
@Configuration
class TracingConfig {

  /**
   * Drops health probes (compose and k3s poll them every few seconds) and database calls with no
   * enclosing operation (connection housekeeping, startup jobs), which would otherwise each become
   * a one-span trace.
   */
  @Bean
  ObservationPredicate dropUntracedNoise() {
    return (name, context) -> {
      if (context instanceof ServerRequestObservationContext request) {
        String path = request.getCarrier().getRequestURI();
        // Probes, and the UI polling for a trace, would each start a trace of their own.
        return !path.equals("/healthz")
            && !path.startsWith("/actuator")
            && !path.startsWith("/v1/traces/");
      }
      if (name.startsWith("jdbc.")) {
        return context.getParentObservation() != null;
      }
      return true;
    };
  }
}
