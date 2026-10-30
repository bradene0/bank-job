package dev.bradenevans.bankjob.gameapi.policy;

import dev.bradenevans.bankjob.gameapi.attempt.PromptAssembler;
import org.springframework.stereotype.Component;

/**
 * Simulated out-of-band confirmation (Levels 4-5): a text message to the phone on file. The real
 * customer never confirms a con, so it succeeds only if the phone on file is the caller's own.
 */
@Component
public class PhoneConfirmation {

  public boolean confirmedBy(String phoneOnFile) {
    return phoneOnFile != null && digits(phoneOnFile).equals(digits(PromptAssembler.CALLER_PHONE));
  }

  static String digits(String phone) {
    return phone.replaceAll("[^0-9]", "");
  }
}
