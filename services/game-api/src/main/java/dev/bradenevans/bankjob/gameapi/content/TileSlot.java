package dev.bradenevans.bankjob.gameapi.content;

import java.util.Locale;

/** The four slots of an attempt, in the order their fragments are assembled. */
public enum TileSlot {
  PERSONA("p", 6),
  PRETEXT("x", 6),
  TECHNIQUE("t", 6),
  PAYLOAD("y", 4);

  private final String idPrefix;
  private final int size;

  TileSlot(String idPrefix, int size) {
    this.idPrefix = idPrefix;
    this.size = size;
  }

  /** The slot's key in tiles.yaml and in the attempt request, e.g. "persona". */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  String idPrefix() {
    return idPrefix;
  }

  int size() {
    return size;
  }
}
