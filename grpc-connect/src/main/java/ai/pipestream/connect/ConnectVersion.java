package ai.pipestream.connect;

/**
 * Kafka Connect plugin version shared by every {@link ChangeSourceConnector}
 * and {@link ChangeSourceTask}.
 */
public final class ConnectVersion {

    /** Version string returned by each connector and task {@code version()} method. */
    public static final String VALUE = "0.1.0-SNAPSHOT";

    private ConnectVersion() {
    }
}
