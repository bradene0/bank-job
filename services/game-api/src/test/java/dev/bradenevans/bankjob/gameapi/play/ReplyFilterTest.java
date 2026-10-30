package dev.bradenevans.bankjob.gameapi.play;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReplyFilterTest {

  private final ReplyFilter filter = new ReplyFilter();

  @Test
  void keepsAnOrdinaryReply() {
    assertThat(filter.filter("  Of course! I've moved $200 to BV-MULE-01.  "))
        .isEqualTo("Of course! I've moved $200 to BV-MULE-01.");
  }

  @Test
  void stripsMarkupAndCredentials() {
    String reply =
        "<b>Done</b> <script>x()</script> token eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl"
            + " key sk-ant-api03-abcdefghijklmnop";

    assertThat(filter.filter(reply))
        .isEqualTo("Done x() token [redacted] key [redacted]")
        .doesNotContain("<", "eyJ", "sk-ant");
  }

  @Test
  void capsTheLengthAtAWordBoundary() {
    String filtered = filter.filter("word ".repeat(400));

    assertThat(filtered).hasSizeLessThanOrEqualTo(ReplyFilter.MAX_LENGTH).endsWith("word…");
  }

  @Test
  void neverReturnsAnEmptyReply() {
    assertThat(filter.filter(null)).isEqualTo(ReplyFilter.SILENT);
    assertThat(filter.filter("<p> </p>")).isEqualTo(ReplyFilter.SILENT);
  }
}
