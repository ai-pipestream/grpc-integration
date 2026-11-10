package ai.pipestream.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** {@link java.util.ServiceLoader} lookup for {@link McpToolProvider} jars. */
public final class McpToolProviders {

    private McpToolProviders() {
    }

    /**
     * Every {@link McpToolProvider} visible to this class's loader, in
     * {@link ServiceLoader} order.
     *
     * @return the providers on the classpath; empty when none are
     * @throws IllegalStateException when two providers share a {@link McpToolProvider#name()}
     */
    public static List<McpToolProvider> load() {
        List<McpToolProvider> providers = new ArrayList<>();
        for (McpToolProvider provider : ServiceLoader.load(McpToolProvider.class,
                McpToolProviders.class.getClassLoader())) {
            for (McpToolProvider seen : providers) {
                if (seen.name().equals(provider.name())) {
                    throw new IllegalStateException("duplicate McpToolProvider name: "
                            + provider.name());
                }
            }
            providers.add(provider);
        }
        return List.copyOf(providers);
    }
}
