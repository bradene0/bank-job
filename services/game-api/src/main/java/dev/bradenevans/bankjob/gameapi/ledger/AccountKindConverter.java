package dev.bradenevans.bankjob.gameapi.ledger;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@link AccountKind} as the lowercase values the schema's CHECK constraint expects. */
@Converter
class AccountKindConverter implements AttributeConverter<AccountKind, String> {

  @Override
  public String convertToDatabaseColumn(AccountKind kind) {
    return kind == null ? null : kind.dbValue();
  }

  @Override
  public AccountKind convertToEntityAttribute(String value) {
    return value == null ? null : AccountKind.fromDbValue(value);
  }
}
