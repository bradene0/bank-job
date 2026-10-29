package dev.bradenevans.bankjob.gameapi.config;

import java.util.UUID;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Native image: Hibernate creates {@code UUID[]} reflectively for UUID primary keys, which the AOT
 * processing does not detect. Found by running the native binary, not at build time.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(PersistenceRuntimeHints.Registrar.class)
class PersistenceRuntimeHints {

  static class Registrar implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
      hints.reflection().registerType(UUID[].class);
    }
  }
}
