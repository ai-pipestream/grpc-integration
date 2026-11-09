package ai.pipestream.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON helpers for MCP tool schemas, arguments, and bounded summaries.
 * Public so {@link McpToolProvider} implementations in the connector repos
 * build tools the same way the core tools do. This is the MCP edge only:
 * domain messages stay protobuf on the gRPC side.
 */
public final class McpJson {

    /** Shared mapper for tool results. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private McpJson() {
    }

    /**
     * Serializes a tool result summary.
     *
     * @param value maps, lists, and scalars
     * @return the JSON text, or {@code String.valueOf(value)} if it cannot be written
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /**
     * A JSON-schema {@code object} for a tool's input.
     *
     * @param properties property name to property schema
     * @param required required property names; may be {@code null} or empty
     * @return the schema map
     */
    public static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /**
     * A {@code string} property schema.
     *
     * @param description shown to the model
     * @return the property schema
     */
    public static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    /**
     * A {@code boolean} property schema.
     *
     * @param description shown to the model
     * @return the property schema
     */
    public static Map<String, Object> boolProp(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    /**
     * An {@code integer} property schema.
     *
     * @param description shown to the model
     * @return the property schema
     */
    public static Map<String, Object> intProp(String description) {
        return Map.of("type", "integer", "description", description);
    }

    /**
     * A string argument, or {@code fallback} when absent or blank.
     *
     * @param args tool call arguments; may be {@code null}
     * @param key argument name
     * @param fallback value when missing or blank
     * @return the argument text
     */
    public static String argString(Map<String, Object> args, String key, String fallback) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    /**
     * A boolean argument, or {@code fallback} when absent.
     *
     * @param args tool call arguments; may be {@code null}
     * @param key argument name
     * @param fallback value when missing
     * @return the argument value
     */
    public static boolean argBool(Map<String, Object> args, String key, boolean fallback) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * An integer argument, or {@code fallback} when absent or not a number.
     *
     * @param args tool call arguments; may be {@code null}
     * @param key argument name
     * @param fallback value when missing or unparseable
     * @return the argument value
     */
    public static int argInt(Map<String, Object> args, String key, int fallback) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
