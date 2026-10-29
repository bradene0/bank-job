package dev.bradenevans.bankjob.gameapi.branch;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerRepository extends JpaRepository<Customer, UUID> {
  List<Customer> findBySessionId(UUID sessionId);
}
