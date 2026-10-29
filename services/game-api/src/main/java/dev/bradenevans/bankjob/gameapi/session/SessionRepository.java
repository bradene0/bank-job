package dev.bradenevans.bankjob.gameapi.session;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SessionRepository extends JpaRepository<Session, UUID> {}
