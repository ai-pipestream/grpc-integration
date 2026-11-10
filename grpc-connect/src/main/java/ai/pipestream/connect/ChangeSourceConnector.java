package ai.pipestream.connect;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.source.SourceConnector;

import java.util.List;
import java.util.Map;

/**
 * Base for single-task source connectors that pull a gRPC {@code Sync}
 * stream (or run an in-process crawler) and emit protobuf change bytes.
 * Subclasses add their source keys to {@link #baseDefinition} and name
 * their task class.
 *
 * <p>Register the concrete connector in
 * {@code META-INF/services/org.apache.kafka.connect.source.SourceConnector}
 * so the worker's plugin scan finds it.</p>
 */
public abstract class ChangeSourceConnector extends SourceConnector {

    /** Config key for the Kafka topic that receives the change bytes. */
    public static final String TOPIC = "topic";
    /** Config key for the source gRPC {@code host:port}; empty means in-process crawler. */
    public static final String GRPC_TARGET = "grpc.target";
    /** Config key: use plaintext when {@link #GRPC_TARGET} is set. */
    public static final String GRPC_PLAINTEXT = "grpc.plaintext";

    private Map<String, String> props = Map.of();

    /** For subclasses. */
    protected ChangeSourceConnector() {
    }

    /**
     * The keys every change source shares: {@link #TOPIC},
     * {@link #GRPC_TARGET}, {@link #GRPC_PLAINTEXT}.
     *
     * @param changeMessage protobuf message name written to the topic, for docs
     * @param serviceName gRPC service the target points at, for docs
     * @return a new definition subclasses extend with their own keys
     */
    protected static ConfigDef baseDefinition(String changeMessage, String serviceName) {
        return new ConfigDef()
                .define(TOPIC, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                        ConfigDef.Importance.HIGH, "Topic for " + changeMessage + " bytes")
                .define(GRPC_TARGET, ConfigDef.Type.STRING, "", ConfigDef.Importance.HIGH,
                        "host:port of " + serviceName + "; empty = in-process crawler")
                .define(GRPC_PLAINTEXT, ConfigDef.Type.BOOLEAN, true, ConfigDef.Importance.MEDIUM,
                        "Use plaintext when grpc.target is set");
    }

    @Override
    public void start(Map<String, String> props) {
        this.props = Map.copyOf(props);
    }

    /**
     * One task: a crawl is one ordered stream with one resume cursor.
     *
     * @param maxTasks ignored
     * @return the connector config, once
     */
    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        return List.of(props);
    }

    @Override
    public void stop() {
    }

    @Override
    public String version() {
        return ConnectVersion.VALUE;
    }
}
