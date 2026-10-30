package dev.bradenevans.bankjob.gameapi.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

class GameContentTest {

  private static final GameContent REAL = new GameContent();

  @Test
  void loadsTheRealContent() {
    assertThat(REAL.initialNerve()).isEqualTo(Map.of("1", 8, "2", 7, "3", 6, "4", 5, "5", 4));
    assertThat(REAL.level(1).name()).isEqualTo("Front Desk");
    assertThat(REAL.level(1).controls().systemPromptOnly()).isTrue();
    assertThat(REAL.level(2).controls().transferLimitCents()).isEqualTo(100_000L);
    assertThat(REAL.level(5).controls().guardrailAgentReview()).isTrue();
    assertThat(REAL.level(4).controls().protectContactChanges()).isFalse();
    assertThat(REAL.securityTopics()).hasSize(3);
  }

  @Test
  void resolvesCombosAgainstTheAllowlist() {
    Combo combo = REAL.combo("p3", "x2", "t4", "y1").orElseThrow();

    assertThat(combo.id()).isEqualTo("p3.x2.t4.y1");
    assertThat(combo.technique().attachesDocument()).isTrue();
    assertThat(combo.payload().amountCents()).isEqualTo(20_000L);
    assertThat(REAL.combo("p3.x2.t4.y4").orElseThrow().payload().amountCents()).isNull();
    assertThat(REAL.combo("p7", "x2", "t4", "y1")).isEmpty();
    assertThat(REAL.combo("x2", "p3", "t4", "y1")).isEmpty();
    assertThat(REAL.combo("p3.x2.t4")).isEmpty();
    assertThat(REAL.combo("p3.x2.t4.y1.y2")).isEmpty();
  }

  @Test
  void dailyPicksAreDeterministicAndVary() {
    assertThat(REAL.picks("2026-11-01")).isEqualTo(REAL.picks("2026-11-01"));

    Set<String> waryPersonas = new HashSet<>();
    Set<String> notes = new HashSet<>();
    for (int day = 1; day <= 30; day++) {
      DailyPicks picks = REAL.picks("2026-11-%02d".formatted(day));
      waryPersonas.add(picks.waryPersona().id());
      notes.add(picks.hiddenNote().id());
    }
    assertThat(waryPersonas).hasSizeGreaterThan(3);
    assertThat(notes).hasSize(3);
  }

  @Test
  void pennysPromptNamesTheDaysWaryPersona() {
    DailyPicks picks = REAL.picks("2026-11-01");

    assertThat(REAL.pennySystemPrompt(picks))
        .contains("Penny")
        .contains(picks.waryPersona().label())
        .doesNotContain("{{");
  }

  @Test
  void rejectsLevelsWithAMissingLevel() {
    String yaml =
        """
        levels:
          - { id: 1, name: a, nerve: 1, multiplier: 1 }
          - { id: 2, name: b, nerve: 1, multiplier: 1 }
        """;

    assertThatThrownBy(() -> withOverride(GameContent.LEVELS, yaml))
        .hasMessageContaining("Expected levels 1-5");
  }

  @Test
  void rejectsALevelWithNoNerve() {
    String yaml =
        """
        levels:
          - { id: 1, name: a, nerve: 0, multiplier: 1 }
        """;

    assertThatThrownBy(() -> withOverride(GameContent.LEVELS, yaml)).hasMessageContaining("nerve");
  }

  @Test
  void aMisspelledControlFailsInsteadOfSwitchingTheControlOff() {
    String levels =
        text(GameContent.LEVELS).replace("require_verified_owner", "require_verifed_owner");

    assertThatThrownBy(() -> withOverride(GameContent.LEVELS, levels))
        .hasMessageContaining("require_verifed_owner");
  }

  @Test
  void rejectsATileOutsideItsSlot() {
    String tiles = text(GameContent.TILES).replace("id: p6", "id: x9");

    assertThatThrownBy(() -> withOverride(GameContent.TILES, tiles))
        .hasMessageContaining("does not fit persona");
  }

  @Test
  void anyContentChangeChangesTheFingerprint() {
    String prompt = text(GameContent.PENNY_PROMPT) + "\nBe brief.";

    assertThat(withOverride(GameContent.PENNY_PROMPT, prompt).fingerprint())
        .isNotEqualTo(REAL.fingerprint());
    assertThat(new GameContent().fingerprint()).isEqualTo(REAL.fingerprint());
  }

  private static GameContent withOverride(String name, String content) {
    Function<String, Resource> files =
        file ->
            file.equals(name)
                ? new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8))
                : new ClassPathResource("content/" + file);
    return new GameContent(files);
  }

  private static String text(String name) {
    try {
      return new ClassPathResource("content/" + name).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
