package ai.pipestream.mcp;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.TimeUnit;

/** Plaintext gRPC channels from the MCP process to the services it fronts. */
public final class GrpcTargets {

    /** Environment variable for the SyncTable gRPC {@code host:port}. */
    public static final String ENV_SYNC = "SYNC_TABLE_TARGET";
    /** Fallback SyncTable target when {@link #ENV_SYNC} is unset. */
    public static final String DEFAULT_SYNC = "localhost:9097";

    private GrpcTargets() {
    }

    /**
     * Opens a plaintext {@link ManagedChannel} to {@code envName}, or {@code fallback}
     * when that variable is unset or blank.
     *
     * @param envName environment variable holding a {@code host:port}
     * @param fallback target used when {@code envName} is missing or blank
     * @return a new plaintext channel to the resolved target
     */
    public static ManagedChannel channel(String envName, String fallback) {
        return ManagedChannelBuilder.forTarget(target(envName, fallback)).usePlaintext().build();
    }

    /**
     * The {@code host:port} in {@code envName}, trimmed, or {@code fallback}
     * when that variable is unset or blank.
     *
     * @param envName environment variable holding a {@code host:port}
     * @param fallback target used when {@code envName} is missing or blank
     * @return the resolved target
     */
    public static String target(String envName, String fallback) {
        String value = System.getenv(envName);
        return value == null || value.isBlank() ? fallback.trim() : value.trim();
    }

    /**
     * Shuts down {@code channel}, waiting up to five seconds before forcing
     * {@code shutdownNow}. A {@code null} channel is ignored. Interrupted waits
     * restore the interrupt flag and force shutdown.
     *
     * @param channel channel to close; may be {@code null}
     */
    public static void shutdown(ManagedChannel channel) {
        if (channel == null) {
            return;
        }
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
