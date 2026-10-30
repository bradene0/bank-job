package dev.bradenevans.bankjob.gameapi.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native image: the MCP SDK (de)serializes its protocol records with Jackson by reflection and
 * ships no hints, so register every type nested in McpSchema.
 */
class McpRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    BindingReflectionHintsRegistrar bindings = new BindingReflectionHintsRegistrar();
    Deque<Class<?>> pending = new ArrayDeque<>();
    pending.add(McpSchema.class);
    while (!pending.isEmpty()) {
      Class<?> type = pending.pop();
      bindings.registerReflectionHints(hints.reflection(), type);
      for (Class<?> nested : type.getDeclaredClasses()) {
        pending.add(nested);
      }
    }
  }
}
