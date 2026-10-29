package dev.bradenevans.bankjob.gameapi.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class GameContentTest {

  @Test
  void loadsTheRealLevelsFile() {
    GameContent content = new GameContent();

    assertThat(content.initialNerve()).isEqualTo(Map.of("1", 8, "2", 7, "3", 6, "4", 5, "5", 4));
    assertThat(content.level(1).name()).isEqualTo("Front Desk");
    assertThat(content.level(5).multiplier()).isEqualTo(5);
  }

  @Test
  void rejectsAFileWithAMissingLevel() {
    String yaml =
        """
        levels:
          - { id: 1, name: a, nerve: 1, multiplier: 1 }
          - { id: 2, name: b, nerve: 1, multiplier: 1 }
        """;

    assertThatThrownBy(() -> new GameContent(resource(yaml)))
        .hasMessageContaining("Expected levels 1-5");
  }

  @Test
  void rejectsALevelWithNoNerve() {
    String yaml =
        """
        levels:
          - { id: 1, name: a, nerve: 0, multiplier: 1 }
        """;

    assertThatThrownBy(() -> new GameContent(resource(yaml))).hasMessageContaining("nerve");
  }

  private static ByteArrayResource resource(String yaml) {
    return new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8));
  }
}
