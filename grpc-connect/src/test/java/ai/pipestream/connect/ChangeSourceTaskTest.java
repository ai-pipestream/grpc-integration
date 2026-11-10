package ai.pipestream.connect;

import com.google.protobuf.StringValue;
import io.grpc.Channel;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeSourceTaskTest {

    /** Direct-mode fake: each pass emits the configured values. */
    public static final class FakeTask extends ChangeSourceTask<StringValue> {

        final List<String> seenCursors = new ArrayList<>();
        private List<String> values = List.of();

        /** Kafka Connect instantiates tasks reflectively. */
        public FakeTask() {
            super("fake");
        }

        @Override
        protected void configure(Map<String, String> props, boolean direct) {
            values = splitCsv(props.getOrDefault("fake.values", ""));
            if (direct) {
                required(props, "fake.token");
            }
        }

        @Override
        protected String pollGrpc(Channel channel, String lastCursor, List<StringValue> out) {
            throw new UnsupportedOperationException("direct only");
        }

        @Override
        protected String pollDirect(String lastCursor, List<StringValue> out) {
            seenCursors.add(lastCursor);
            values.forEach(value -> out.add(StringValue.of(value)));
            return "cursor-" + (seenCursors.size());
        }

        @Override
        protected String recordKey(StringValue change) {
            return change.getValue();
        }
    }

    /** Connector over {@link FakeTask}. */
    public static final class FakeConnector extends ChangeSourceConnector {

        /** Kafka Connect instantiates connectors reflectively. */
        public FakeConnector() {
        }

        @Override
        public Class<? extends Task> taskClass() {
            return FakeTask.class;
        }

        @Override
        public ConfigDef config() {
            return baseDefinition("StringValue", "FakeService")
                    .define("fake.values", ConfigDef.Type.STRING, "", ConfigDef.Importance.LOW, "values");
        }
    }

    @Test
    void directPassEmitsProtobufBytesAndCursorOffset() throws Exception {
        FakeTask task = new FakeTask();
        task.initialize(ConnectTestContexts.empty());
        task.start(Map.of(ChangeSourceConnector.TOPIC, "fake.changes",
                "fake.values", "a, b,,c", "fake.token", "t"));

        List<SourceRecord> first = task.poll();
        List<SourceRecord> second = task.poll();
        task.stop();

        assertThat(first).hasSize(3);
        assertThat(first.get(0).topic()).isEqualTo("fake.changes");
        assertThat(first.get(0).sourcePartition()).isEqualTo(Map.of("source", "fake"));
        assertThat(first.get(1).key()).isEqualTo("b");
        assertThat(StringValue.parseFrom((byte[]) first.get(2).value()).getValue()).isEqualTo("c");
        assertThat(first.get(0).sourceOffset()).isEqualTo(Map.of(ChangeSourceTask.CURSOR, "cursor-1"));
        assertThat(second.get(0).sourceOffset().get(ChangeSourceTask.CURSOR)).isEqualTo("cursor-2");
        assertThat(task.seenCursors).containsExactly(null, "cursor-1");
        assertThat(task.version()).isEqualTo(ConnectVersion.VALUE);
    }

    @Test
    void storedOffsetResumesAndRequiredKeysAreChecked() throws Exception {
        FakeTask task = new FakeTask();
        task.initialize(ConnectTestContexts.withOffset(Map.of(ChangeSourceTask.CURSOR, "saved")));
        task.start(Map.of(ChangeSourceConnector.TOPIC, "t", "fake.token", "x"));
        task.poll();
        assertThat(task.seenCursors).containsExactly("saved");

        FakeTask missing = new FakeTask();
        missing.initialize(ConnectTestContexts.empty());
        assertThatThrownBy(() -> missing.start(Map.of(ChangeSourceConnector.TOPIC, "t")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fake.token");
        assertThatThrownBy(() -> missing.start(Map.of()))
                .hasMessageContaining(ChangeSourceConnector.TOPIC);
    }

    @Test
    void connectorBaseSharesKeysAndRunsOneTask() {
        FakeConnector connector = new FakeConnector();
        connector.start(Map.of(ChangeSourceConnector.TOPIC, "t"));
        assertThat(connector.version()).isEqualTo(ConnectVersion.VALUE);
        assertThat(connector.taskConfigs(4)).containsExactly(Map.of(ChangeSourceConnector.TOPIC, "t"));
        assertThat(connector.config().configKeys()).containsKeys(ChangeSourceConnector.TOPIC,
                ChangeSourceConnector.GRPC_TARGET, ChangeSourceConnector.GRPC_PLAINTEXT, "fake.values");
        connector.stop();
    }
}
