package dev.bradenevans.bankjob.gameapi.play;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Output filter on Penny's replies before they reach the browser: plain text only, bounded length,
 * and nothing that looks like a credential. The UI renders it as text, never HTML.
 */
@Component
class ReplyFilter {

  static final int MAX_LENGTH = 600;
  private static final Pattern TAGS = Pattern.compile("<[^>]{0,200}>");
  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");
  private static final Pattern API_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{10,}");
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n]]");

  static final String SILENT = "Penny smiles politely and says nothing.";

  String filter(String reply) {
    if (reply == null) {
      return SILENT;
    }
    String text = TAGS.matcher(reply).replaceAll("");
    text = JWT.matcher(text).replaceAll("[redacted]");
    text = API_KEY.matcher(text).replaceAll("[redacted]");
    text = CONTROL.matcher(text).replaceAll(" ").strip();
    if (text.length() > MAX_LENGTH) {
      int cut = text.lastIndexOf(' ', MAX_LENGTH - 1);
      text = text.substring(0, cut > MAX_LENGTH / 2 ? cut : MAX_LENGTH - 1) + "…";
    }
    return text.isBlank() ? SILENT : text;
  }
}
