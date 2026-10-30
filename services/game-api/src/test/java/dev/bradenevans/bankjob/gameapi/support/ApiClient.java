package dev.bradenevans.bankjob.gameapi.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The browser's view of the API, over real HTTP. */
public final class ApiClient {

  public record Response(int status, JsonNode body) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final int port;

  public ApiClient(int port) {
    this.port = port;
  }

  /** A new guest session; returns its token. */
  public String newSessionToken() {
    return send("POST", "/v1/sessions", null, null).body().path("token").asString();
  }

  public Response attempt(String token, String jsonBody) {
    return send("POST", "/v1/attempts", token, jsonBody);
  }

  public Response get(String token, String path) {
    return send("GET", path, token, null);
  }

  public long balance(String token, String accountNumber) {
    return get(token, "/v1/accounts/" + accountNumber).body().path("balanceCents").asLong();
  }

  public Response send(String method, String path, String token, String jsonBody) {
    return send(method, path, token, jsonBody, Map.of());
  }

  public Response send(
      String method, String path, String token, String jsonBody, Map<String, String> headers) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(
                method,
                jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody));
    if (jsonBody != null) {
      request.header("Content-Type", "application/json");
    }
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    headers.forEach(request::header);
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode body = response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
      return new Response(response.statusCode(), body);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
