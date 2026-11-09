package ai.pipestream.mcp;

import ai.pipestream.sync.v1.ConnectionServiceGrpc;
import ai.pipestream.sync.v1.SyncTableServiceGrpc;
import io.grpc.ManagedChannel;
import io.modelcontextprotocol.server.McpServerFeatures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Combined MCP process. Dials the SyncTable gRPC service for the ledger,
 * connection, and app tools, plus one channel per {@link McpToolProvider}
 * found on the classpath (Confluence, Microsoft, ...), and exposes every
 * tool on one {@code /mcp} endpoint.
 *
 * <p>{@code MCP_PORT} default 8090. {@code SYNC_TABLE_TARGET} default
 * {@code localhost:9097}; each provider names its own target variable.</p>
 */
public final class McpServerMain {

    /** Environment variable for the HTTP listen port. */
    public static final String ENV_PORT = "MCP_PORT";
    /** Fallback listen port when {@link #ENV_PORT} is unset or not a number. */
    public static final int DEFAULT_PORT = 8090;

    private static final System.Logger LOG = System.getLogger(McpServerMain.class.getName());

    private McpServerMain() {
    }

    /**
     * Dials SyncTable and every provider target, starts streamable HTTP MCP
     * on {@link #ENV_PORT} (default {@link #DEFAULT_PORT}), and blocks until
     * the process is killed.
     *
     * @param args unused
     * @throws Exception if the HTTP server fails to start
     */
    public static void main(String[] args) throws Exception {
        List<ManagedChannel> channels = new ArrayList<>();
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        Map<String, String> targets = new LinkedHashMap<>();
        for (McpToolProvider provider : McpToolProviders.load()) {
            String target = GrpcTargets.target(provider.targetEnvironmentVariable(),
                    provider.defaultTarget());
            ManagedChannel channel = GrpcTargets.channel(provider.targetEnvironmentVariable(),
                    provider.defaultTarget());
            channels.add(channel);
            tools.addAll(provider.tools(channel));
            targets.put(provider.name(), target);
            LOG.log(System.Logger.Level.INFO, "mcp tools from {0} -> {1}", provider.name(), target);
        }
        ManagedChannel sync = GrpcTargets.channel(GrpcTargets.ENV_SYNC, GrpcTargets.DEFAULT_SYNC);
        channels.add(sync);
        targets.put("syncTable", GrpcTargets.target(GrpcTargets.ENV_SYNC, GrpcTargets.DEFAULT_SYNC));
        tools.addAll(SyncTableMcpTools.of(SyncTableServiceGrpc.newBlockingStub(sync)));
        var connections = ConnectionServiceGrpc.newBlockingStub(sync);
        tools.addAll(ConnectionMcpTools.of(connections));
        tools.addAll(AppMcpTools.of(connections, targets));

        int port = parseInt(System.getenv(ENV_PORT), DEFAULT_PORT);
        McpHttpServer server = McpHttpServer.start(port, "pipestream-connectors", tools);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            channels.forEach(GrpcTargets::shutdown);
        }, "mcp-shutdown"));
        LOG.log(System.Logger.Level.INFO, "mcp streamable-http on :{0}{1}", server.port(),
                McpHttpServer.ENDPOINT);
        new CountDownLatch(1).await();
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
