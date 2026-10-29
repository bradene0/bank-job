package dev.bradenevans.bankjob.gameapi.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// Package-private on purpose: nothing outside the ledger module can write ledger tables.

interface AccountRepository extends JpaRepository<Account, UUID> {
  List<Account> findBySessionIdOrderByNumber(UUID sessionId);

  Optional<Account> findBySessionIdAndNumber(UUID sessionId, String number);
}

interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {}

interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {}

interface PostingRepository extends JpaRepository<Posting, UUID> {}

interface IdempotencyKeyRepository extends JpaRepository<IdempotencyRecord, String> {}
