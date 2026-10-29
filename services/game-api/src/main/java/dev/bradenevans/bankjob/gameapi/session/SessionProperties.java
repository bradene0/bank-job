package dev.bradenevans.bankjob.gameapi.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxPerIpPerHour session creations allowed per client IP per hour (each provisions rows, so
 *     this bounds database growth from scripted abuse)
 * @param ipHashSalt HMAC key for hashing client IPs; from SSM in production
 * @param trustCfConnectingIp read the client IP from Cloudflare's CF-Connecting-IP header. Only
 *     safe when the origin accepts traffic from Cloudflare alone (security group rule).
 */
@ConfigurationProperties("bankjob.sessions")
public record SessionProperties(
    int maxPerIpPerHour, String ipHashSalt, boolean trustCfConnectingIp) {}
