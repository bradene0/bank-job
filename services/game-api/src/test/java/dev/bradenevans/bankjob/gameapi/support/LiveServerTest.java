package dev.bradenevans.bankjob.gameapi.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The application on a real port against Testcontainers Postgres, for tests that speak HTTP the way
 * the agent and the browser do (MCP over streamable HTTP, POST /v1/attempts). The agent is the
 * in-process {@link FakeAgent}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeAgent.Config.class})
public @interface LiveServerTest {}
