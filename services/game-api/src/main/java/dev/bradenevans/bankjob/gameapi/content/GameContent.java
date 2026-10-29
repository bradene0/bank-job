package dev.bradenevans.bankjob.gameapi.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Game content loaded from the repo's content/ directory, which the build copies onto the
 * classpath. Content is validated at startup so a bad edit fails fast instead of mid-game.
 */
@Component
@ImportRuntimeHints(ContentRuntimeHints.class)
@RegisterReflectionForBinding({GameContent.LevelsFile.class, LevelDefinition.class})
public class GameContent {

  static final String LEVELS_PATH = "content/levels.yaml";
  private static final Set<Integer> EXPECTED_LEVEL_IDS = Set.of(1, 2, 3, 4, 5);

  record LevelsFile(List<LevelDefinition> levels) {}

  private final SortedMap<Integer, LevelDefinition> levels;

  public GameContent() {
    this(new ClassPathResource(LEVELS_PATH));
  }

  GameContent(Resource levelsYaml) {
    YAMLMapper mapper =
        YAMLMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    try (InputStream in = levelsYaml.getInputStream()) {
      this.levels = index(mapper.readValue(in, LevelsFile.class));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + LEVELS_PATH, e);
    }
  }

  private static SortedMap<Integer, LevelDefinition> index(LevelsFile file) {
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
}
