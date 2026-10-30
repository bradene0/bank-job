package dev.bradenevans.bankjob.gameapi.content;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/** Native image: include the content files, which are loaded by path at runtime. */
class ContentRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.resources().registerPattern("content/*.yaml");
    hints.resources().registerPattern("content/prompts/*.md");
  }
}
