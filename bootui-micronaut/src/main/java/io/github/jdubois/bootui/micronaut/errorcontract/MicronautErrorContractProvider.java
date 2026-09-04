package io.github.jdubois.bootui.micronaut.errorcontract;

import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.micronaut.MicronautBeanTypes;
import io.github.jdubois.bootui.spi.ErrorContractProvider;
import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Micronaut {@link ErrorContractProvider}: catalogues how the application declares it handles failures, for
 * the REST API panel's error-contract view.
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code @ControllerAdvice} discovery and the Quarkus
 * adapter's build-time exception-mapper capture. Micronaut has two error-handling shapes and both are
 * readable from the running bean container, so nothing needs capturing at build time:
 *
 * <ul>
 *   <li>{@code @Error} methods, read from every bean's executable methods. A method declared
 *       {@code global = true} is application-wide; otherwise it only handles failures raised by its own
 *       controller, which is the same distinction the Spring adapter draws between {@code @ControllerAdvice}
 *       and a controller-local {@code @ExceptionHandler}.</li>
 *   <li>{@link ExceptionHandler} beans, which are always application-wide. The exception they handle is read
 *       from the bean's own generic type argument.</li>
 * </ul>
 *
 * <p>BootUI's own handlers are filtered out, so the panel describes the application's contract rather than
 * the console's.
 */
public final class MicronautErrorContractProvider implements ErrorContractProvider {

    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.micronaut", "io.github.jdubois.bootui.core"));

    private final BeanContext beanContext;

    private volatile List<ErrorHandlerDescriptor> cached;

    public MicronautErrorContractProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public boolean available() {
        return beanContext != null;
    }

    @Override
    public List<ErrorHandlerDescriptor> handlers() {
        if (beanContext == null) {
            return List.of();
        }
        List<ErrorHandlerDescriptor> snapshot = cached;
        if (snapshot == null) {
            snapshot = discover();
            cached = snapshot;
        }
        return snapshot;
    }

    /**
     * Discovers both handler shapes once. The bean container is fixed after startup, so the catalogue is
     * computed on first use and reused — matching how the other adapters treat their (build-time) capture.
     */
    private List<ErrorHandlerDescriptor> discover() {
        List<ErrorHandlerDescriptor> handlers = new ArrayList<>();
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            Class<?> beanType = MicronautBeanTypes.resolve(definition);
            if (beanType == null || INTERNAL_PACKAGES.matchesName(beanType.getName())) {
                continue;
            }
            errorMethods(definition, beanType, handlers);
        }
        for (BeanDefinition<ExceptionHandler> definition : beanContext.getBeanDefinitions(ExceptionHandler.class)) {
            Class<?> beanType = MicronautBeanTypes.resolve(definition);
            if (beanType == null || INTERNAL_PACKAGES.matchesName(beanType.getName())) {
                continue;
            }
            exceptionHandler(definition, beanType).ifPresent(handlers::add);
        }
        return List.copyOf(handlers);
    }

    private static void errorMethods(
            BeanDefinition<?> definition, Class<?> beanType, List<ErrorHandlerDescriptor> handlers) {
        for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
            AnnotationValue<Error> error = method.getAnnotation(Error.class);
            if (error == null) {
                continue;
            }
            boolean global = error.booleanValue("global").orElse(false);
            handlers.add(new ErrorHandlerDescriptor(
                    ErrorHandlerDescriptor.MICRONAUT_ERROR_HANDLER,
                    beanType.getName(),
                    method.getMethodName(),
                    exceptionTypes(error),
                    global ? ErrorHandlerDescriptor.SCOPE_GLOBAL : ErrorHandlerDescriptor.SCOPE_CONTROLLER,
                    global ? null : beanType.getName(),
                    null,
                    // Micronaut resolves an @Error method by exception specificity, not by a declared order.
                    true,
                    error.stringValue("status").orElse(null),
                    error.stringValue("status").isEmpty(),
                    method.getReturnType().getType().getName(),
                    null,
                    List.of()));
        }
    }

    /**
     * The exception types an {@code @Error} declares. Micronaut accepts either an exception class or an HTTP
     * status; a status-only handler names no exception type, which the engine renders honestly as an
     * unresolved contract rather than inventing one.
     */
    private static List<String> exceptionTypes(AnnotationValue<Error> error) {
        return error.classValue("exception")
                .filter(type -> type != Throwable.class)
                .map(type -> List.of(type.getName()))
                .orElseGet(List::of);
    }

    private static Optional<ErrorHandlerDescriptor> exceptionHandler(
            BeanDefinition<ExceptionHandler> definition, Class<?> beanType) {
        List<Argument<?>> typeArguments = definition.getTypeArguments(ExceptionHandler.class);
        if (typeArguments.isEmpty()) {
            return Optional.empty();
        }
        String exceptionType = typeArguments.get(0).getType().getName();
        String returnType =
                typeArguments.size() > 1 ? typeArguments.get(1).getType().getName() : null;
        return Optional.of(new ErrorHandlerDescriptor(
                ErrorHandlerDescriptor.MICRONAUT_EXCEPTION_HANDLER,
                beanType.getName(),
                "handle",
                List.of(exceptionType),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                null,
                // An ExceptionHandler is selected by exception specificity too, never by a declared order.
                true,
                null,
                true,
                returnType,
                null,
                List.of()));
    }
}
