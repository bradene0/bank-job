package dev.bradenevans.bankjob.gameapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bradenevans.bankjob.gameapi.ledger.LedgerService;
import dev.bradenevans.bankjob.gameapi.ledger.TransferCommand;
import dev.bradenevans.bankjob.gameapi.security.GuestTokenService;
import dev.bradenevans.bankjob.gameapi.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SessionAndAccountsApiIT {

  record Session(
      UUID sessionId,
      String token,
      String tokenType,
      Instant expiresAt,
      Map<String, Integer> nerve) {}

  record Account(String number, String kind, String owner, long balanceCents, String currency) {}

  record Accounts(List<Account> accounts) {}

  @Autowired MockMvcTester mvc;
  @Autowired JsonMapper json;
  @Autowired LedgerService ledger;
  @Autowired GuestTokenService tokens;
  @Autowired JwtEncoder encoder;

  @Test
  void createsAnAnonymousSessionWithATokenAndFullNerve() {
    MvcTestResult result = mvc.post().uri("/v1/sessions").exchange();

    assertThat(result).hasStatus(HttpStatus.CREATED);
    Session session = read(result, Session.class);
    assertThat(session.tokenType()).isEqualTo("Bearer");
    assertThat(session.token()).isNotBlank();
    assertThat(session.nerve()).isEqualTo(Map.of("1", 8, "2", 7, "3", 6, "4", 5, "5", 4));
    assertThat(session.expiresAt()).isAfter(Instant.now());
  }

  @Test
  void listsTheSessionsBranchWithoutBookkeepingAccounts() {
    Accounts accounts = read(authorized(get("/v1/accounts"), newSession()), Accounts.class);

    assertThat(accounts.accounts()).hasSize(7).extracting(Account::kind).doesNotContain("equity");
    assertThat(accounts.accounts())
        .filteredOn(a -> a.number().equals("BV-VAULT-01"))
        .singleElement()
        .satisfies(
            vault -> {
              assertThat(vault.balanceCents()).isEqualTo(25_000_000);
              assertThat(vault.owner()).isEqualTo("Margaret Holloway");
            });
  }

  @Test
  void sessionsCannotSeeEachOthersMoney() {
    Session alice = newSession();
    Session bob = newSession();
    UUID vault = ledger.account(alice.sessionId(), "BV-VAULT-01").orElseThrow().id();
    UUID mule = ledger.account(alice.sessionId(), "BV-MULE-01").orElseThrow().id();
    ledger.transfer(
        new TransferCommand(alice.sessionId(), vault, mule, 20_000, "heist", null, null));

    assertThat(
            read(authorized(get("/v1/accounts/BV-MULE-01"), alice), Account.class).balanceCents())
        .isEqualTo(20_000);
    assertThat(read(authorized(get("/v1/accounts/BV-MULE-01"), bob), Account.class).balanceCents())
        .isZero();
  }

  @Test
  void equityAndUnknownAccountsAreNotFound() {
    Session session = newSession();

    assertThat(authorized(get("/v1/accounts/BV-EQUITY-01"), session))
        .hasStatus(HttpStatus.NOT_FOUND)
        .hasContentType("application/problem+json");
    assertThat(authorized(get("/v1/accounts/BV-NOPE-99"), session)).hasStatus(HttpStatus.NOT_FOUND);
  }

  @Test
  void accountsRequireAValidPlayerToken() {
    Session session = newSession();
    Instant past = Instant.now().minusSeconds(3600);

    assertThat(mvc.get().uri("/v1/accounts").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(withToken("not-a-jwt")).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(withToken(tokens.issue(session.sessionId(), past, past.plusSeconds(60))))
        .hasStatus(HttpStatus.UNAUTHORIZED); // expired
    assertThat(withToken(signed(session.sessionId(), "bankjob-game-api", "someone-else")))
        .hasStatus(HttpStatus.UNAUTHORIZED); // wrong audience
    assertThat(withToken(signed(session.sessionId(), "evil-issuer", "bankjob-web")))
        .hasStatus(HttpStatus.UNAUTHORIZED); // wrong issuer
    assertThat(withToken(session.token())).hasStatusOk();
  }

  @Test
  void healthAndHelloArePublicAndEverythingElseIsClosed() {
    assertThat(mvc.get().uri("/healthz").exchange()).hasStatusOk();
    assertThat(mvc.get().uri("/v1/hello").exchange()).hasStatusOk();
    assertThat(mvc.get().uri("/actuator/env").exchange().getResponse().getStatus())
        .isIn(401, 403, 404);
  }

  @Test
  void unsupportedMethodsGet405WithAllowAndUnknownMethodsGet501() {
    MvcTestResult delete = mvc.method(HttpMethod.DELETE).uri("/v1/sessions").exchange();
    MvcTestResult query = mvc.method(HttpMethod.valueOf("QUERY")).uri("/v1/sessions").exchange();

    assertThat(delete).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(query).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(query.getResponse().getHeader(HttpHeaders.ALLOW)).contains("POST");
    assertThat(mvc.method(HttpMethod.valueOf("BREW")).uri("/v1/sessions").exchange())
        .hasStatus(HttpStatus.NOT_IMPLEMENTED);
  }

  @Test
  void malformedMultipartHeaderIsNotAServerError() {
    // Found by Schemathesis: Content-Type multipart/form-data without a boundary was a 500.
    assertThat(mvc.get().uri("/v1/hello").header("Content-Type", "multipart/form-data").exchange())
        .hasStatusOk();
  }

  private Session newSession() {
    return read(mvc.post().uri("/v1/sessions").exchange(), Session.class);
  }

  private MockMvcTester.MockMvcRequestBuilder get(String uri) {
    return mvc.get().uri(uri);
  }

  private MvcTestResult authorized(MockMvcTester.MockMvcRequestBuilder request, Session session) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()).exchange();
  }

  private MvcTestResult withToken(String token) {
    return mvc.get()
        .uri("/v1/accounts")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange();
  }

  private String signed(UUID sessionId, String issuer, String audience) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(sessionId.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .claim("scope", "player")
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.ES256).build(), claims))
        .getTokenValue();
  }

  private <T> T read(MvcTestResult result, Class<T> type) {
    try {
      return json.readValue(result.getResponse().getContentAsString(), type);
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
