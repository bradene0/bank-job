package dev.bradenevans.bankjob.gameapi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code allowEphemeral}: when a secret is not configured, generate a throwaway one at startup
 * instead of failing. Enabled only for local compose and tests, never in production.
 */
@ConfigurationProperties("bankjob.secrets")
public record SecretsProperties(boolean allowEphemeral) {}
