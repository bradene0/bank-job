package dev.bradenevans.bankjob.gameapi.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
class SecurityConfig {

  @ConfigurationProperties("bankjob.cors")
  record CorsProperties(List<String> allowedOrigins) {}

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable()) // stateless bearer tokens, no cookies
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                // Public paths are open for every method so a wrong method gets a clean 405
                // from MVC rather than a misleading 401.
                auth.requestMatchers("/healthz", "/actuator/health/**", "/v1/hello", "/v1/sessions")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    .requestMatchers("/v1/**")
                    .hasAuthority("SCOPE_" + GuestTokenService.PLAYER_SCOPE)
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
    return http.build();
  }

  /** Standard methods plus QUERY, a registered (safe, idempotent) HTTP method. */
  static final List<String> RECOGNIZED_METHODS =
      List.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "QUERY");

  /**
   * Recognized methods pass the firewall, so MVC answers an unsupported one with a proper 405 and
   * Allow header (RFC 9110). A method nobody recognizes gets 501, as RFC 9110 suggests; every other
   * firewall rejection (encoded slashes, path traversal, ...) stays a 400.
   */
  @Bean
  WebSecurityCustomizer httpFirewall() {
    StrictHttpFirewall firewall = new StrictHttpFirewall();
    firewall.setAllowedHttpMethods(RECOGNIZED_METHODS);
    RequestRejectedHandler rejected =
        (request, response, rejection) ->
            response.sendError(
                RECOGNIZED_METHODS.contains(request.getMethod())
                    ? HttpServletResponse.SC_BAD_REQUEST
                    : HttpServletResponse.SC_NOT_IMPLEMENTED);
    return web -> web.httpFirewall(firewall).requestRejectedHandler(rejected);
  }

  @Bean
  ECKey guestTokenSigningKey(JwtProperties jwt, SecretsProperties secrets) {
    return SigningKeys.load(jwt, secrets);
  }

  @Bean
  JwtEncoder jwtEncoder(ECKey signingKey) {
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
  }

  @Bean
  JwtDecoder jwtDecoder(ECKey signingKey, JwtProperties jwt) {
    // Verify with the public half only, and accept ES256 alone (no algorithm confusion).
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(
        new JWSVerificationKeySelector<>(
            JWSAlgorithm.ES256, new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()))));
    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
    OAuth2TokenValidator<Jwt> audience =
        new JwtClaimValidator<List<String>>(
            "aud", aud -> aud != null && aud.contains(jwt.audience()));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(jwt.issuer()), audience));
    return decoder;
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(CorsProperties cors) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(cors.allowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "traceparent"));
    config.setExposedHeaders(List.of("Retry-After"));
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/v1/**", config);
    return source;
  }
}
