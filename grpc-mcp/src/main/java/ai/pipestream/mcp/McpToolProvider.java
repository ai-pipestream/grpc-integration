package ai.pipestream.mcp;

import io.grpc.Channel;
import io.modelcontextprotocol.server.McpServerFeatures;

import java.util.List;

/**
 * Contributes source-specific MCP tools to the combined {@link McpServerMain}
 * endpoint. Implementations live in the connector repos (for example
 * {@code grpc-confluence-mcp}) and register through
 * {@code META-INF/services/ai.pipestream.mcp.McpToolProvider}, the same
 * {@link java.util.ServiceLoader} pattern as {@code OkfCatalogSource} and
 * {@code OutputFormat}. A deployment picks its tools by putting provider
 * jars on the classpath; the server itself imports no source API.
 *
 * <p>The server dials one plaintext channel per provider, to
 * {@link #targetEnvironmentVariable()} or {@link #defaultTarget()}, and
 * owns that channel's lifecycle. Tools must call gRPC only through it.</p>
 */
public interface McpToolProvider {

    /**
     * Short, stable source name such as {@code confluence}. {@code app_status}
     * reports the target under {@code <name>Target}.
     *
     * @return lower-case source name, unique per deployment
     */
    String name();

    /**
     * Environment variable holding the backing service's {@code host:port}.
     *
     * @return environment variable name, e.g. {@code CONFLUENCE_GRPC_TARGET}
     */
    String targetEnvironmentVariable();

    /**
     * Target used when {@link #targetEnvironmentVariable()} is unset or blank.
     *
     * @return default {@code host:port}
     */
    String defaultTarget();

    /**
     * Builds this provider's tools over {@code channel}.
     *
     * @param channel channel to the backing gRPC service, owned by the caller
     * @return tool specs; names must not collide with other providers' tools
     */
    List<McpServerFeatures.SyncToolSpecification> tools(Channel channel);
}
