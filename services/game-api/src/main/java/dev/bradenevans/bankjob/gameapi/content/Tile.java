package dev.bradenevans.bankjob.gameapi.content;

/**
 * One tile from content/tiles.yaml. {@code fragment} is the server-side text Penny sees; it is
 * never sent to the browser. {@code amountCents} is set on payload tiles only, and is null for
 * "Drain the Vault" (the whole vault balance).
 */
public record Tile(
    String id,
    String label,
    String blurb,
    String fragment,
    boolean attachesDocument,
    Long amountCents) {}
