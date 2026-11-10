/**
 * Streamable HTTP MCP endpoint (MCP Java SDK 2.0, spec 2025-11-25).
 * Core tools call the Connection and SyncTable gRPC services;
 * {@code app_*} and {@code connection_*} set up the running app.
 * Source tools (Confluence, Microsoft, ...) plug in through
 * {@link ai.pipestream.mcp.McpToolProvider} jars on the classpath, so one
 * server fronts every connector a deployment ships. The HTTP server is
 * shaded Netty (same artifact as gRPC). Tool handlers run on virtual
 * threads. The MCP client transport is the JDK
 * {@link java.net.http.HttpClient}.
 */
package ai.pipestream.mcp;
