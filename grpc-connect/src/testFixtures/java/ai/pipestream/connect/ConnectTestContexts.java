package ai.pipestream.connect;

import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** {@link SourceTaskContext} fakes for source task tests. */
public final class ConnectTestContexts {

    private ConnectTestContexts() {
    }

    /**
     * A context with no stored offsets.
     *
     * @return the context
     */
    public static SourceTaskContext empty() {
        return withOffset(null);
    }

    /**
     * A context whose reader answers {@code offset} for every partition.
     *
     * @param offset stored offset; {@code null} means none
     * @return the context
     */
    public static SourceTaskContext withOffset(Map<String, Object> offset) {
        return new SourceTaskContext() {
            @Override
            public OffsetStorageReader offsetStorageReader() {
                return new OffsetStorageReader() {
                    @Override
                    public <T> Map<Map<String, T>, Map<String, Object>> offsets(
                            Collection<Map<String, T>> partitions) {
                        Map<Map<String, T>, Map<String, Object>> out = new HashMap<>();
                        if (offset != null) {
                            partitions.forEach(partition -> out.put(partition, offset));
                        }
                        return out;
                    }

                    @Override
                    public <T> Map<String, Object> offset(Map<String, T> partition) {
                        return offset;
                    }
                };
            }

            @Override
            public Map<String, String> configs() {
                return Map.of();
            }
        };
    }
}
