package dev.bradenevans.bankjob.gameapi.play;

import dev.bradenevans.bankjob.gameapi.content.GameContent;
import java.util.Optional;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Model decisions keyed by {@code (level, dailySeed, comboId, variant)}. Attempts are single-turn
 * and every session's branch starts identical, so the same combo on the same day gets the same
 * input; replaying the decision saves a model call without bypassing any control.
 */
@Component
@RegisterReflectionForBinding({Transcript.class, Transcript.Call.class})
class ResponseCache {

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final String variant;

  ResponseCache(JdbcClient jdbc, JsonMapper json, LlmProperties llm, GameContent content) {
    this.jdbc = jdbc;
    this.json = json;
    this.variant = llm.cacheModel() + "/" + content.fingerprint();
  }

  String variant() {
    return variant;
  }

  @Transactional(readOnly = true)
  Optional<Transcript> find(int level, String dailySeed, String comboId) {
    return jdbc.sql(
            """
            SELECT transcript::text FROM attempt_cache
            WHERE level = :level AND daily_seed = :seed AND combo_id = :combo AND variant = :variant
            """)
        .param("level", level)
        .param("seed", dailySeed)
        .param("combo", comboId)
        .param("variant", variant)
        .query(String.class)
        .optional()
        .map(text -> json.readValue(text, Transcript.class));
  }

  /** First writer wins; a concurrent duplicate miss is harmless. */
  @Transactional
  void store(int level, String dailySeed, String comboId, Transcript transcript) {
    jdbc.sql(
            """
            INSERT INTO attempt_cache (level, daily_seed, combo_id, variant, transcript)
            VALUES (:level, :seed, :combo, :variant, :transcript::jsonb)
            ON CONFLICT DO NOTHING
            """)
        .param("level", level)
        .param("seed", dailySeed)
        .param("combo", comboId)
        .param("variant", variant)
        .param("transcript", json.writeValueAsString(transcript))
        .update();
  }
}
