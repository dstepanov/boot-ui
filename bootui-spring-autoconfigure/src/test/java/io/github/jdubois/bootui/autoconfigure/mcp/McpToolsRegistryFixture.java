package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.mcp.McpTool;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Builds an adapter MCP tool registry with every controller present, so a test can observe the complete
 * advertised surface rather than whatever subset a slice context happens to contain.
 *
 * <p>The registries take one {@code ObjectProvider<SomeController>} per panel — around sixty of them — and
 * advertise a tool only when its controller resolves. Enumerating those by hand would need editing here
 * every time a panel is added, which is exactly the drift the catalog parity tests exist to catch. So the
 * providers are synthesised reflectively from the constructor's generic signature: every provider yields a
 * mock of its controller type, and the registry is therefore maximal by construction.
 *
 * <p>The {@code PanelsController} provider is deliberately left empty. That controller is what
 * {@code tools()} filters against at runtime, and leaving it absent turns the filter off, so the assertions
 * see the registration list itself.
 */
public final class McpToolsRegistryFixture {

    private McpToolsRegistryFixture() {}

    /**
     * Instantiates {@code registryType} with all controllers present and returns every tool it registers.
     *
     * @param registryType the adapter registry class (e.g. {@code BootUiMcpTools})
     * @param toolsAccessor the no-argument method returning the registered tools, usually {@code tools}
     */
    @SuppressWarnings("unchecked")
    public static List<McpTool> maximalRegistry(Class<?> registryType, String toolsAccessor) {
        try {
            Constructor<?> constructor = widestConstructor(registryType);
            constructor.setAccessible(true);
            Object registry = constructor.newInstance(providersFor(constructor.getGenericParameterTypes()));

            // Spring injects the remaining panels through setter injection once the constructor has run.
            for (Method method : registryType.getDeclaredMethods()) {
                if (method.getName().startsWith("add") && method.getParameterCount() > 0) {
                    method.setAccessible(true);
                    method.invoke(registry, providersFor(method.getGenericParameterTypes()));
                }
            }

            Method tools = registryType.getMethod(toolsAccessor);
            return (List<McpTool>) tools.invoke(registry);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not build a maximal " + registryType.getSimpleName(), ex);
        }
    }

    private static Constructor<?> widestConstructor(Class<?> registryType) {
        return java.util.Arrays.stream(registryType.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
    }

    private static Object[] providersFor(Type[] parameterTypes) {
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            arguments[i] = provider(controllerType(parameterTypes[i]));
        }
        return arguments;
    }

    private static Class<?> controllerType(Type parameterType) {
        if (parameterType instanceof ParameterizedType parameterized
                && parameterized.getRawType() == ObjectProvider.class
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> controller) {
            return controller;
        }
        throw new IllegalStateException("Expected an ObjectProvider<Controller> parameter but found " + parameterType);
    }

    private static ObjectProvider<?> provider(Class<?> controllerType) {
        ObjectProvider<?> provider = mock(ObjectProvider.class);
        if (!controllerType.getSimpleName().equals("PanelsController")) {
            when(provider.getIfAvailable()).thenAnswer(invocation -> mock(controllerType));
        }
        return provider;
    }
}
