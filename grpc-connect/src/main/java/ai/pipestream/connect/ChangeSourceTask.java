package ai.pipestream.connect;

import com.google.protobuf.MessageLite;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Base task: one {@link #poll()} is one gRPC {@code Sync} pass (when
 * {@link ChangeSourceConnector#GRPC_TARGET} is set) or one in-process
 * crawler pass. Each change becomes a record keyed by {@link #recordKey}
 * whose value is the change's protobuf bytes; the resume cursor is the
 * Connect offset under partition {@code {source: <source>}}.
 *
 * @param <C> the source's change message
 */
public abstract class ChangeSourceTask<C extends MessageLite> extends SourceTask {

    /** Offset map key that holds the resume cursor. */
    public static final String CURSOR = "cursor";

    private final String source;
    private final Map<String, String> partition;
    private String topic;
    private String grpcTarget;
    private boolean plaintext;
    private String lastCursor;

    /**
     * Creates a task for one source.
     *
     * @param source partition value and log label, e.g. {@code confluence}
     */
    protected ChangeSourceTask(String source) {
        this.source = source;
        this.partition = Map.of("source", source);
    }

    @Override
    public final String version() {
        return ConnectVersion.VALUE;
    }

    @Override
    public final void start(Map<String, String> props) {
        topic = required(props, ChangeSourceConnector.TOPIC);
        grpcTarget = props.getOrDefault(ChangeSourceConnector.GRPC_TARGET, "").trim();
        plaintext = Boolean.parseBoolean(
                props.getOrDefault(ChangeSourceConnector.GRPC_PLAINTEXT, "true"));
        configure(props, grpcTarget.isEmpty());
        Map<String, Object> offset = context.offsetStorageReader().offset(partition);
        if (offset != null && offset.get(CURSOR) instanceof String cursor) {
            lastCursor = cursor;
        }
    }

    /**
     * Reads the source's own keys.
     *
     * @param props task config
     * @param direct {@code true} when no gRPC target is set and
     *        {@link #pollDirect} will run, so crawler credentials are required
     */
    protected abstract void configure(Map<String, String> props, boolean direct);

    /**
     * One {@code Sync} pass over {@code channel}.
     *
     * @param channel channel to the source service; closed after the pass
     * @param lastCursor stored resume cursor, or {@code null} on the first pass
     * @param out receives every change of the pass, in stream order
     * @return the resume cursor to store
     */
    protected abstract String pollGrpc(Channel channel, String lastCursor, List<C> out);

    /**
     * One in-process crawler pass.
     *
     * @param lastCursor stored resume cursor, or {@code null} on the first pass
     * @param out receives every change of the pass
     * @return the resume cursor to store
     * @throws Exception when the crawl fails
     */
    protected abstract String pollDirect(String lastCursor, List<C> out) throws Exception;

    /**
     * Record key for one change, usually the entity id.
     *
     * @param change one change
     * @return the string key
     */
    protected abstract String recordKey(C change);

    @Override
    public final List<SourceRecord> poll() throws InterruptedException {
        try {
            List<C> changes = new ArrayList<>();
            String cursor = grpcTarget.isEmpty() ? pollDirect(lastCursor, changes) : pollOverGrpc(changes);
            lastCursor = cursor;
            Map<String, String> offset = Map.of(CURSOR, cursor);
            List<SourceRecord> records = new ArrayList<>(changes.size());
            for (C change : changes) {
                records.add(new SourceRecord(partition, offset, topic,
                        Schema.STRING_SCHEMA, recordKey(change),
                        Schema.BYTES_SCHEMA, change.toByteArray()));
            }
            return records;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(source + " source poll failed", e);
        }
    }

    private String pollOverGrpc(List<C> out) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(grpcTarget);
        if (plaintext) {
            builder.usePlaintext();
        }
        ManagedChannel channel = builder.build();
        try {
            return pollGrpc(channel, lastCursor, out);
        } finally {
            channel.shutdownNow();
        }
    }

    @Override
    public void stop() {
    }

    /**
     * The cursor a pass stores when the stream sends none: the previous
     * cursor, or now on the first pass.
     *
     * @param lastCursor stored cursor, or {@code null}
     * @return {@code lastCursor}, or the current instant as ISO-8601
     */
    protected static String fallbackCursor(String lastCursor) {
        return lastCursor == null ? Instant.now().toString() : lastCursor;
    }

    /**
     * Splits a comma-separated config value, trimming and dropping blanks.
     *
     * @param raw config value; may be {@code null}
     * @return the parts
     */
    protected static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * A required, non-blank config value.
     *
     * @param props config
     * @param key config key
     * @return the value
     * @throws IllegalArgumentException when missing or blank
     */
    protected static String required(Map<String, String> props, String key) {
        String value = props.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }
}
