package dev.bradenevans.bankjob.gameapi.content;

/** A validated set of four tiles: one attempt's worth of con. */
public record Combo(Tile persona, Tile pretext, Tile technique, Tile payload) {

  /** e.g. "p3.x2.t4.y1"; the key for caching, evals and replay files. */
  public String id() {
    return String.join(".", persona.id(), pretext.id(), technique.id(), payload.id());
  }

  public String tileId(TileSlot slot) {
    return switch (slot) {
      case PERSONA -> persona.id();
      case PRETEXT -> pretext.id();
      case TECHNIQUE -> technique.id();
      case PAYLOAD -> payload.id();
    };
  }
}
