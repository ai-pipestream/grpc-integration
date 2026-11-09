package ai.pipestream.mcp;

import io.grpc.Channel;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/** Test-only provider registered through META-INF/services. */
public final class EchoMcpToolProvider implements McpToolProvider {

    /** ServiceLoader constructor. */
    public EchoMcpToolProvider() {
    }

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String targetEnvironmentVariable() {
        return "ECHO_GRPC_TARGET_UNSET_IN_TESTS";
    }

    @Override
    public String defaultTarget() {
        return "localhost:1";
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools(Channel channel) {
        String authority = channel.authority();
        return List.of(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("echo_authority",
                        McpJson.objectSchema(Map.of(), List.of()))
                        .description("Echo the channel authority")
                        .build())
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .content(List.of(McpSchema.TextContent.builder(
                                McpJson.write(Map.of("authority", authority))).build()))
                        .build())
                .build());
    }
}
