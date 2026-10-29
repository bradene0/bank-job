package dev.bradenevans.bankjob.gameapi.content;

/** One level from content/levels.yaml. Controls are added when the policy engine lands. */
public record LevelDefinition(int id, String name, int nerve, int multiplier) {}
