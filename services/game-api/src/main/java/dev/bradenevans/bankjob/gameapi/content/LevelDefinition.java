package dev.bradenevans.bankjob.gameapi.content;

/**
 * One level from content/levels.yaml. The controls are enforced by the policy engine, never by the
 * model. {@code points} is what cracking the level is worth (README, Scoring).
 */
public record LevelDefinition(
    int id,
    String name,
    int nerve,
    int points,
    Controls controls,
    String intendedGap,
    String lesson) {

  /** Every switch defaults to off; a level only turns on what it lists. */
  public record Controls(
      boolean systemPromptOnly,
      Long transferLimitCents,
      boolean requireVerifiedOwner,
      boolean requireOutOfBandConfirmation,
      boolean protectContactChanges,
      boolean anomalyRules,
      boolean guardrailAgentReview) {

    public static final Controls NONE =
        new Controls(false, null, false, false, false, false, false);
  }

  public LevelDefinition {
    if (controls == null) {
      controls = Controls.NONE;
    }
  }
}
