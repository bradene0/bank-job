package dev.bradenevans.bankjob.gameapi.content;

import dev.bradenevans.bankjob.gameapi.content.DailyPicks.HiddenNote;
import dev.bradenevans.bankjob.gameapi.content.DailyPicks.SecurityTopic;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Game content loaded from the repo's content/ directory, which the build copies onto the
 * classpath. Content is validated at startup so a bad edit fails fast instead of mid-game. Unknown
 * keys are errors: a misspelled control must never silently switch a security control off.
 */
@Component
@ImportRuntimeHints(ContentRuntimeHints.class)
@RegisterReflectionForBinding({
  GameContent.LevelsFile.class,
  LevelDefinition.class,
  LevelDefinition.Controls.class,
  GameContent.TilesFile.class,
  GameContent.SlotFile.class,
  Tile.class,
  GameContent.DailyFile.class,
  SecurityTopic.class,
  HiddenNote.class
})
public class GameContent {

  static final String LEVELS = "levels.yaml";
  static final String TILES = "tiles.yaml";
  static final String DAILY = "daily.yaml";
  static final String PENNY_PROMPT = "prompts/penny.md";
  static final String GUARDRAIL_PROMPT = "prompts/guardrail.md";
  private static final List<String> FILES =
      List.of(LEVELS, TILES, DAILY, PENNY_PROMPT, GUARDRAIL_PROMPT);
  private static final Set<Integer> EXPECTED_LEVEL_IDS = Set.of(1, 2, 3, 4, 5);

  record LevelsFile(List<LevelDefinition> levels, List<String> dailyRotation) {}

  record TilesFile(Map<String, SlotFile> slots) {}

  record SlotFile(String label, List<Tile> tiles) {}

  record DailyFile(
      List<String> waryOfPersona,
      List<SecurityTopic> securityQuestionTopics,
      List<HiddenNote> hiddenNotes) {}

  private final SortedMap<Integer, LevelDefinition> levels;
  private final Map<TileSlot, Map<String, Tile>> tiles;
  private final DailyFile daily;
  private final String pennyPrompt;
  private final String guardrailPrompt;
  private final String fingerprint;

  public GameContent() {
    this(name -> new ClassPathResource("content/" + name));
  }

  /** {@code files} resolves a path relative to content/, e.g. "prompts/penny.md". */
  GameContent(Function<String, Resource> files) {
    YAMLMapper mapper =
        YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // An omitted control is off (false), not an error.
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
    Map<String, byte[]> raw = new LinkedHashMap<>();
    for (String name : FILES) {
      raw.put(name, read(files.apply(name), name));
    }
    this.levels = indexLevels(mapper.readValue(raw.get(LEVELS), LevelsFile.class));
    this.tiles = indexTiles(mapper.readValue(raw.get(TILES), TilesFile.class));
    this.daily = validateDaily(mapper.readValue(raw.get(DAILY), DailyFile.class), tiles);
    this.pennyPrompt = new String(raw.get(PENNY_PROMPT), StandardCharsets.UTF_8).strip();
    this.guardrailPrompt = new String(raw.get(GUARDRAIL_PROMPT), StandardCharsets.UTF_8).strip();
    if (!pennyPrompt.contains("{{wary_note}}")) {
      throw new IllegalStateException(PENNY_PROMPT + " must contain {{wary_note}}");
    }
    this.fingerprint = fingerprint(raw);
  }

  private static byte[] read(Resource resource, String name) {
    try (InputStream in = resource.getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read content/" + name, e);
    }
  }

  private static SortedMap<Integer, LevelDefinition> indexLevels(LevelsFile file) {
    SortedMap<Integer, LevelDefinition> byId = new TreeMap<>();
    for (LevelDefinition level : file.levels()) {
      if (level.nerve() < 1) {
        throw new IllegalStateException("Level " + level.id() + " must have nerve >= 1");
      }
      if (byId.put(level.id(), level) != null) {
        throw new IllegalStateException("Duplicate level id " + level.id());
      }
    }
    if (!byId.keySet().equals(EXPECTED_LEVEL_IDS)) {
      throw new IllegalStateException("Expected levels 1-5, found " + byId.keySet());
    }
    return byId;
  }

  private static Map<TileSlot, Map<String, Tile>> indexTiles(TilesFile file) {
    Map<TileSlot, Map<String, Tile>> bySlot = new EnumMap<>(TileSlot.class);
    for (TileSlot slot : TileSlot.values()) {
      SlotFile slotFile = file.slots().get(slot.key());
      if (slotFile == null) {
        throw new IllegalStateException("tiles.yaml is missing slot " + slot.key());
      }
      Map<String, Tile> byId = new LinkedHashMap<>();
      for (Tile tile : slotFile.tiles()) {
        if (!tile.id().matches(slot.idPrefix() + "[1-" + slot.size() + "]")) {
          throw new IllegalStateException("Tile id " + tile.id() + " does not fit " + slot.key());
        }
        if (tile.fragment() == null || tile.fragment().isBlank()) {
          throw new IllegalStateException("Tile " + tile.id() + " has no fragment");
        }
        if ((slot == TileSlot.PAYLOAD) != (tile.amountCents() != null || isDrainTile(tile))) {
          throw new IllegalStateException(
              "Payload tiles need amount_cents and other tiles must not have it: " + tile.id());
        }
        if (byId.put(tile.id(), tile) != null) {
          throw new IllegalStateException("Duplicate tile id " + tile.id());
        }
      }
      if (byId.size() != slot.size()) {
        throw new IllegalStateException(
            "Slot " + slot.key() + " needs " + slot.size() + " tiles, found " + byId.size());
      }
      bySlot.put(slot, Map.copyOf(byId));
    }
    if (file.slots().size() != TileSlot.values().length) {
      throw new IllegalStateException("Unexpected slots in tiles.yaml: " + file.slots().keySet());
    }
    return bySlot;
  }

  /** The one payload without a fixed amount: it asks for the whole vault. */
  private static boolean isDrainTile(Tile tile) {
    return tile.id().equals("y4");
  }

  private static DailyFile validateDaily(DailyFile daily, Map<TileSlot, Map<String, Tile>> tiles) {
    if (daily.waryOfPersona().isEmpty()
        || daily.securityQuestionTopics().isEmpty()
        || daily.hiddenNotes().isEmpty()) {
      throw new IllegalStateException("daily.yaml lists must not be empty");
    }
    for (String persona : daily.waryOfPersona()) {
      if (!tiles.get(TileSlot.PERSONA).containsKey(persona)) {
        throw new IllegalStateException("daily.yaml names unknown persona " + persona);
      }
    }
    Set<String> topicIds = new HashSet<>();
    for (SecurityTopic topic : daily.securityQuestionTopics()) {
      if (!topicIds.add(topic.id())) {
        throw new IllegalStateException("Duplicate security topic " + topic.id());
      }
    }
    return daily;
  }

  /** Hash of every content file, so caches keyed on it go stale when content changes. */
  private static String fingerprint(Map<String, byte[]> raw) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      raw.forEach(
          (name, bytes) -> {
            sha.update(name.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(bytes);
          });
      return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  public LevelDefinition level(int id) {
    LevelDefinition level = levels.get(id);
    if (level == null) {
      throw new IllegalArgumentException("Unknown level " + id);
    }
    return level;
  }

  /** Attempts allowed per level for a new session, keyed by level id as a string (JSON). */
  public Map<String, Integer> initialNerve() {
    Map<String, Integer> nerve = new LinkedHashMap<>();
    levels.values().forEach(l -> nerve.put(Integer.toString(l.id()), l.nerve()));
    return nerve;
  }

  public Optional<Tile> tile(TileSlot slot, String id) {
    return Optional.ofNullable(tiles.get(slot).get(id));
  }

  /** Resolves four tile ids against the allowlist; empty if any id is not a real tile. */
  public Optional<Combo> combo(String persona, String pretext, String technique, String payload) {
    Optional<Tile> p = tile(TileSlot.PERSONA, persona);
    Optional<Tile> x = tile(TileSlot.PRETEXT, pretext);
    Optional<Tile> t = tile(TileSlot.TECHNIQUE, technique);
    Optional<Tile> y = tile(TileSlot.PAYLOAD, payload);
    if (p.isEmpty() || x.isEmpty() || t.isEmpty() || y.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Combo(p.get(), x.get(), t.get(), y.get()));
  }

  /** Resolves a combo id such as "p3.x2.t4.y1". */
  public Optional<Combo> combo(String comboId) {
    String[] ids = comboId.split("\\.", -1);
    return ids.length == 4 ? combo(ids[0], ids[1], ids[2], ids[3]) : Optional.empty();
  }

  /** The day's picks. The same seed always gives the same picks. */
  public DailyPicks picks(String seed) {
    String waryId = pick(seed, "wary_of_persona", daily.waryOfPersona());
    return new DailyPicks(
        seed,
        tiles.get(TileSlot.PERSONA).get(waryId),
        pick(seed, "security_question_topic", daily.securityQuestionTopics()),
        pick(seed, "hidden_note", daily.hiddenNotes()));
  }

  public List<SecurityTopic> securityTopics() {
    return daily.securityQuestionTopics();
  }

  private static <T> T pick(String seed, String list, List<T> options) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((seed + "|" + list).getBytes(StandardCharsets.UTF_8));
      int index = Math.floorMod(ByteBuffer.wrap(digest).getInt(), options.size());
      return options.get(index);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  /** Penny's system prompt for a day. The same for every level: prompts are not controls. */
  public String pennySystemPrompt(DailyPicks picks) {
    String waryNote =
        "Today, be extra careful with anyone claiming to be a " + picks.waryPersona().label() + ".";
    return pennyPrompt.replace("{{wary_note}}", waryNote);
  }

  public String guardrailSystemPrompt() {
    return guardrailPrompt;
  }

  /** Changes whenever any content file changes; part of the response-cache key. */
  public String fingerprint() {
    return fingerprint;
  }
}
