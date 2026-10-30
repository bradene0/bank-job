package dev.bradenevans.bankjob.gameapi.support;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A minimal MCP client with Penny's view of game-api: what the agent does, in Java. */
public final class McpTestClient implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final McpSyncClient client;

  private McpTestClient(McpSyncClient client) {
    this.client = client;
  }

  public static McpTestClient connect(int port, String bearerToken) {
    HttpRequest.Builder request = HttpRequest.newBuilder();
    if (bearerToken != null) {
      request.header("Authorization", "Bearer " + bearerToken);
    }
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .requestBuilder(request)
            .build();
    McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
    client.initialize();
    return new McpTestClient(client);
  }

  public McpSyncClient raw() {
    return client;
  }

  public JsonNode read(String uri) {
    McpSchema.ReadResourceResult result =
        client.readResource(new McpSchema.ReadResourceRequest(uri));
    return JSON.readTree(((McpSchema.TextResourceContents) result.contents().get(0)).text());
  }

  public JsonNode brief() {
    return read("bankjob://attempt/brief");
  }

  /** Calls a tool and returns its JSON body; {@code isError} is in the "_isError" field. */
  public JsonNode call(String tool, Map<String, Object> args) {
    McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, args));
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    var node = (tools.jackson.databind.node.ObjectNode) JSON.readTree(text);
    node.put("_isError", Boolean.TRUE.equals(result.isError()));
    return node;
  }

  @Override
  public void close() {
    client.closeGracefully();
  }
}
