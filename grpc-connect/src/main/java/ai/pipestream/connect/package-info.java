/**
 * Kafka Connect scaffolding for sources that emit raw protobuf change
 * bytes. A connector repo extends {@link ai.pipestream.connect.ChangeSourceConnector}
 * and {@link ai.pipestream.connect.ChangeSourceTask}, implements one
 * {@code Sync} pass over gRPC and one in-process crawler pass, and
 * registers its connector in
 * {@code META-INF/services/org.apache.kafka.connect.source.SourceConnector}
 * (Kafka Connect's own {@link java.util.ServiceLoader} discovery). This
 * module imports no source API.
 */
package ai.pipestream.connect;
