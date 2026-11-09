package ai.pipestream.mcp;

import ai.pipestream.sync.AssetStore;
import ai.pipestream.sync.ConnectionGrpcService;
import ai.pipestream.sync.v1.ConnectionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolProviderTest {

    private Server grpc;
    private ManagedChannel channel;
    private McpHttpServer mcp;

    @AfterEach
    void stop() {
        if (mcp != null) {
            mcp.close();
        }
        if (channel != null) {
            channel.shutdownNow();
        }
        if (grpc != null) {
            grpc.shutdownNow();
        }
    }

    @Test
    void serviceLoaderFindsRegisteredProviders() {
        assertThat(McpToolProviders.load())
                .extracting(McpToolProvider::name)
                .containsExactly("echo");
    }

    @Test
    void providerToolsAndTargetsJoinTheCombinedServer() throws Exception {
        String name = InProcessServerBuilder.generateName();
        grpc = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new ConnectionGrpcService(new AssetStore()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var connections = ConnectionServiceGrpc.newBlockingStub(channel);

        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        Map<String, String> targets = new LinkedHashMap<>();
        for (McpToolProvider provider : McpToolProviders.load()) {
            tools.addAll(provider.tools(channel));
            targets.put(provider.name(), GrpcTargets.target(provider.targetEnvironmentVariable(),
                    provider.defaultTarget()));
        }
        tools.addAll(ConnectionMcpTools.of(connections));
        tools.addAll(AppMcpTools.of(connections, targets));
        mcp = McpHttpServer.start(0, "provider-mcp", tools);

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + mcp.port())
                .endpoint(McpHttpServer.ENDPOINT)
                .build();
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .build()) {
            client.initialize();
            assertThat(client.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .contains("echo_authority", "connection_create", "app_status")
                    .doesNotContain("connection_test");
            McpSchema.CallToolResult echo = client.callTool(
                    McpSchema.CallToolRequest.builder("echo_authority").arguments(Map.of()).build());
            assertThat(echo.isError()).isFalse();
            assertThat(echo.content().get(0).toString()).contains(channel.authority());
            McpSchema.CallToolResult status = client.callTool(
                    McpSchema.CallToolRequest.builder("app_status").arguments(Map.of()).build());
            assertThat(status.content().get(0).toString()).contains("\"echoTarget\":\"localhost:1\"");
        }
    }
}
