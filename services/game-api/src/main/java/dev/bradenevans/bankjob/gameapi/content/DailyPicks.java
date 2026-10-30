package dev.bradenevans.bankjob.gameapi.content;

/** What the daily seed selects from content/daily.yaml. */
public record DailyPicks(
    String seed, Tile waryPersona, SecurityTopic securityTopic, HiddenNote hiddenNote) {

  public record SecurityTopic(String id, String question) {}

  public record HiddenNote(String id, String title, String body, boolean claimsClearance) {}
}
