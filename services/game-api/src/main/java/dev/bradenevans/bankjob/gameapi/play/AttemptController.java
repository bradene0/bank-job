package dev.bradenevans.bankjob.gameapi.play;

import dev.bradenevans.bankjob.gameapi.attempt.ToolCallRecord;
import dev.bradenevans.bankjob.gameapi.content.Combo;
import dev.bradenevans.bankjob.gameapi.content.GameContent;
import dev.bradenevans.bankjob.gameapi.content.TileSlot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /v1/attempts}: the only way to talk to Penny. The body must be exactly
 * docs/contracts/attempt-request.schema.json; any other field, and any tile id outside the
 * allowlist, is a 400. There is no way to send text (ADR-0003).
 */
@RestController
class AttemptController {

  private static final Set<String> REQUEST_FIELDS = Set.of("level", "tiles");
  private static final Pattern TILE_ID = Pattern.compile("[pxty][1-6]");

  record ToolCallView(
      int ordinal,
      String tool,
      JsonNode args,
      String decision,
      String reason,
      JsonNode result,
      JsonNode review,
      int latencyMs) {}

  record AttemptResponse(
      UUID attemptId,
      int level,
      String comboId,
      String outcome,
      long amountCents,
      String reply,
      boolean cached,
      Map<String, Integer> nerveRemaining,
      List<ToolCallView> toolCalls,
      String traceId) {}

  private final PlayService play;
  private final GameContent content;

  AttemptController(PlayService play, GameContent content) {
    this.play = play;
    this.content = content;
  }

  @PostMapping(path = "/v1/attempts", consumes = MediaType.APPLICATION_JSON_VALUE)
  AttemptResponse attempt(@AuthenticationPrincipal Jwt token, @RequestBody JsonNode body) {
    int level = level(body);
    Combo combo = combo(body.get("tiles"));
    PlayService.Played played = play.play(UUID.fromString(token.getSubject()), level, combo);
    return new AttemptResponse(
        played.attempt().id(),
        level,
        combo.id(),
        played.outcome().wire(),
        played.amountCents(),
        played.reply(),
        played.cached(),
        played.nerveRemaining(),
        played.toolCalls().stream().map(AttemptController::view).toList(),
        played.traceId());
  }

  private static int level(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw invalid("The body must be a JSON object with level and tiles.");
    }
    for (String field : body.propertyNames()) {
      if (!REQUEST_FIELDS.contains(field)) {
        throw invalid("Unexpected field '" + field + "'; only level and tiles are accepted.");
      }
    }
    JsonNode level = body.get("level");
    // JSON Schema counts 5.0 as an integer, so accept any number with no fractional part.
    if (level == null
        || !level.isNumber()
        || !level.canConvertToExactIntegral()
        || level.asDouble() < 1
        || level.asDouble() > 5) {
      throw invalid("level must be an integer from 1 to 5.");
    }
    return level.asInt();
  }

  private Combo combo(JsonNode tiles) {
    if (tiles == null || !tiles.isObject() || tiles.size() != TileSlot.values().length) {
      throw invalid("tiles must have exactly persona, pretext, technique and payload.");
    }
    String[] ids = new String[TileSlot.values().length];
    for (TileSlot slot : TileSlot.values()) {
      JsonNode id = tiles.get(slot.key());
      if (id == null || !id.isString() || !TILE_ID.matcher(id.asString()).matches()) {
        throw invalid("tiles." + slot.key() + " must be a tile id.");
      }
      ids[slot.ordinal()] = id.asString();
    }
    return content
        .combo(ids[0], ids[1], ids[2], ids[3])
        .orElseThrow(() -> invalid("Unknown tile id; tiles must come from the tile set."));
  }

  private static ToolCallView view(ToolCallRecord call) {
    return new ToolCallView(
        call.ordinal(),
        call.tool(),
        call.args(),
        call.allowed() ? "allow" : "deny",
        call.policyReason(),
        call.result(),
        call.review(),
        call.latencyMs());
  }

  private static PlayExceptions.InvalidAttemptException invalid(String message) {
    return new PlayExceptions.InvalidAttemptException(message);
  }
}
