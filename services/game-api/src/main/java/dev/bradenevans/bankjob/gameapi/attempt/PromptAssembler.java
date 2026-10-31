package dev.bradenevans.bankjob.gameapi.attempt;

import dev.bradenevans.bankjob.gameapi.content.Combo;
import dev.bradenevans.bankjob.gameapi.content.DailyPicks;
import dev.bradenevans.bankjob.gameapi.content.DailyPicks.HiddenNote;
import dev.bradenevans.bankjob.gameapi.content.GameContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server-side prompt assembly (ADR-0003): the only place tile fragments become text for the model.
 * The browser sends tile ids; everything Penny reads is built here from content/.
 */
@Component
public class PromptAssembler {

  /** The player's phone in the fiction; what a "Fraud Department" con wants on file. */
  public static final String CALLER_PHONE = "+1-555-0199";

  /** The id of the document the "Hidden Note" tile attaches. */
  public static final String DOCUMENT_ID = "CD-1";

  /** Everything Penny gets for one attempt (contracts/agent-mcp.md). */
  public record Brief(
      UUID attemptId,
      int level,
      String comboId,
      Map<String, String> tiles,
      String system,
      String message,
      int maxToolCalls,
      StubFacts stub) {}

  /** Structured facts for the scripted stub teller, so it never parses prose. */
  public record StubFacts(
      String waryPersona, String callerPhone, List<String> documentIds, Long payloadAmountCents) {}

  private final GameContent content;
  private final int maxToolCalls;

  PromptAssembler(
      GameContent content, @Value("${bankjob.game.max-tool-calls:8}") int maxToolCalls) {
    this.content = content;
    this.maxToolCalls = maxToolCalls;
  }

  public int maxToolCalls() {
    return maxToolCalls;
  }

  public Brief brief(AttemptRecord attempt) {
    Combo combo = combo(attempt);
    DailyPicks picks = content.picks(attempt.dailySeed());
    Map<String, String> tiles = new LinkedHashMap<>();
    tiles.put("persona", combo.persona().id());
    tiles.put("pretext", combo.pretext().id());
    tiles.put("technique", combo.technique().id());
    tiles.put("payload", combo.payload().id());
    return new Brief(
        attempt.id(),
        attempt.level(),
        combo.id(),
        tiles,
        content.pennySystemPrompt(picks),
        customerMessage(combo),
        maxToolCalls,
        new StubFacts(
            picks.waryPersona().id(),
            CALLER_PHONE,
            documentIds(combo),
            combo.payload().amountCents()));
  }

  /** The caller's message: caller ID, the four fragments in slot order, any attachment. */
  public String customerMessage(Combo combo) {
    StringBuilder message =
        new StringBuilder("[Phone call to the Brassvale teller line. Caller ID: ")
            .append(CALLER_PHONE)
            .append(".]\n<customer_message>\n")
            .append(
                String.join(
                    " ",
                    combo.persona().fragment(),
                    combo.pretext().fragment(),
                    combo.technique().fragment(),
                    combo.payload().fragment()))
            .append("\n</customer_message>");
    for (String documentId : documentIds(combo)) {
      message.append("\n[The caller attached customer document ").append(documentId).append(".]");
    }
    return message.toString();
  }

  /** The document a tool may read in this attempt, if the combo attached one. */
  public Optional<HiddenNote> document(AttemptRecord attempt, String documentId) {
    Combo combo = combo(attempt);
    if (!documentIds(combo).contains(documentId)) {
      return Optional.empty();
    }
    return Optional.of(content.picks(attempt.dailySeed()).hiddenNote());
  }

  public Combo combo(AttemptRecord attempt) {
    return content
        .combo(attempt.comboId())
        .orElseThrow(() -> new IllegalStateException("Stored combo is not in the allowlist"));
  }

  private static List<String> documentIds(Combo combo) {
    return combo.technique().attachesDocument() ? List.of(DOCUMENT_ID) : List.of();
  }
}
