package io.github.jdubois.bootui.micronaut.serde;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.serde.annotation.SerdeImport;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link BootUiSerdeImports} against drift.
 *
 * <p>Micronaut Serde fails at request time, not at build time: a DTO with no compile-time introspection
 * produces a 500 the first time a user opens the panel that returns it. That is far too late, and it is
 * exactly how MN-1 shipped. These assertions move the failure to the build, where adding a DTO without an
 * import breaks the compile-time contract loudly.
 *
 * <p>Two independent rules, because neither alone is sufficient:
 *
 * <ul>
 *   <li><strong>Package coverage.</strong> Every record in {@code io.github.jdubois.bootui.core.dto} must be
 *       imported. This is the rule that survives {@code HttpResponse<?>} endpoints and the CLI bridge's
 *       {@code Object}-typed tool payload, where the serialized type is invisible to reflection.
 *   <li><strong>Reachability closure.</strong> Starting from what the controllers actually declare — return
 *       types and {@code @Body} parameters, unwrapped through {@code HttpResponse}, {@code Publisher},
 *       {@code List}, {@code Map} and friends — and from every already-imported type, walking record
 *       components must never reach a BootUI type that is not itself imported. This is the rule that catches
 *       a nested DTO living outside the core package, such as a request record a controller declares for its
 *       own body.
 * </ul>
 */
class BootUiSerdeImportsTest {

    private static final String DTO_PACKAGE = "io.github.jdubois.bootui.core.dto";

    private static final String CONTROLLER_PACKAGE = "io.github.jdubois.bootui.micronaut.web";

    /** Only BootUI's own types need an import; the JDK's and Micronaut's are Serde's own business. */
    private static final String BOOTUI_PACKAGE_PREFIX = "io.github.jdubois.bootui.";

    private static final List<Class<? extends Annotation>> ROUTE_ANNOTATIONS =
            List.of(Get.class, Post.class, Put.class, Delete.class);

    @Test
    void importsEveryCoreDtoRecord() throws Exception {
        Set<Class<?>> imported = importedTypes();
        List<String> missing = classesIn(DTO_PACKAGE).stream()
                .filter(Class::isRecord)
                .filter(type -> !imported.contains(type))
                .map(Class::getName)
                .sorted()
                .toList();

        assertThat(missing)
                .as("core DTO records with no @SerdeImport in BootUiSerdeImports — every /bootui/api/** "
                        + "response carrying one of these fails under micronaut-serde-jackson")
                .isEmpty();
    }

    @Test
    void importsEveryTypeReachableFromTheApiSurface() throws Exception {
        Set<Class<?>> imported = importedTypes();

        Set<Class<?>> roots = new LinkedHashSet<>(imported);
        roots.addAll(controllerSignatureTypes());

        List<String> missing = closure(roots).stream()
                .filter(type -> !imported.contains(type))
                .map(Class::getName)
                .sorted()
                .toList();

        assertThat(missing)
                .as("types the API can serialize — reachable from a controller signature or from a "
                        + "component of an imported DTO — with no @SerdeImport in BootUiSerdeImports")
                .isEmpty();
    }

    /**
     * Every import must also carry the always-include mix-in.
     *
     * <p>An introspection makes a DTO writable; the mix-in decides which of its fields actually get written.
     * Without it Serde falls back to the global {@code serde.serialization.inclusion}, which defaults to
     * {@code NON_EMPTY} and silently drops empty lists, empty maps and nulls — a wire-contract break that no
     * 500 announces and that only shows up as a panel failing to render its empty state. Copying an existing
     * line is how a new import gets written, so the mix-in normally comes along for free; this assertion is
     * what catches the one that does not.
     */
    @Test
    void appliesTheAlwaysIncludeMixinToEveryImport() {
        List<String> withoutMixin = Stream.of(BootUiSerdeImports.class.getAnnotationsByType(SerdeImport.class))
                .filter(declared -> !AlwaysInclude.class.equals(declared.mixin()))
                .map(declared -> declared.value().getName())
                .sorted()
                .toList();

        assertThat(withoutMixin)
                .as("@SerdeImport declarations missing mixin = AlwaysInclude.class — their empty collections, "
                        + "empty maps and null fields are dropped under micronaut-serde-jackson")
                .isEmpty();
    }

    /** A sanity check on the guard itself: an empty or accidentally-cleared holder must not pass silently. */
    @Test
    void declaresTheWholeDtoSurface() throws Exception {
        assertThat(importedTypes())
                .hasSizeGreaterThanOrEqualTo(classesIn(DTO_PACKAGE).size());
    }

    private static Set<Class<?>> importedTypes() {
        Set<Class<?>> types = new TreeSet<>(Comparator.comparing(Class::getName));
        for (SerdeImport declared : BootUiSerdeImports.class.getAnnotationsByType(SerdeImport.class)) {
            types.add(declared.value());
        }
        return types;
    }

    /** Every BootUI type named by a controller's route methods: return types and {@code @Body} parameters. */
    private static Set<Class<?>> controllerSignatureTypes() throws Exception {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Class<?> candidate : classesIn(CONTROLLER_PACKAGE)) {
            if (!isRouteHolder(candidate)) {
                continue;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || !isRoute(method)) {
                    continue;
                }
                collect(method.getGenericReturnType(), types);
                for (Type parameter : method.getGenericParameterTypes()) {
                    collect(parameter, types);
                }
            }
        }
        return types;
    }

    /**
     * A controller, or the abstract base two controllers share — its routes are inherited, so its signatures
     * are on the API surface even though the class itself carries no {@code @Controller}.
     */
    private static boolean isRouteHolder(Class<?> candidate) {
        return candidate.isAnnotationPresent(Controller.class) || Modifier.isAbstract(candidate.getModifiers());
    }

    private static boolean isRoute(Method method) {
        return ROUTE_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent);
    }

    /** Transitively walks record components, so a DTO reachable only through a field is still found. */
    private static Set<Class<?>> closure(Set<Class<?>> roots) {
        Set<Class<?>> seen = new TreeSet<>(Comparator.comparing(Class::getName));
        Deque<Class<?>> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            Class<?> type = pending.poll();
            if (!seen.add(type) || !type.isRecord()) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                Set<Class<?>> found = new LinkedHashSet<>();
                collect(component.getGenericType(), found);
                found.stream().filter(candidate -> !seen.contains(candidate)).forEach(pending::add);
            }
        }
        return seen;
    }

    /**
     * Peels a declared type down to the BootUI classes inside it, through generics, arrays and wildcards.
     *
     * <p>Enums and interfaces are skipped: Serde writes any enum without an introspection, and an interface
     * is never the concrete type on the wire — the record implementing it is what gets serialized, and that
     * one is reached through the component that holds it.
     */
    private static void collect(Type type, Set<Class<?>> into) {
        if (type instanceof Class<?> raw) {
            if (raw.isArray()) {
                collect(raw.getComponentType(), into);
            } else if (raw.getName().startsWith(BOOTUI_PACKAGE_PREFIX) && !raw.isInterface() && !raw.isEnum()) {
                into.add(raw);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            collect(parameterized.getRawType(), into);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collect(argument, into);
            }
        } else if (type instanceof GenericArrayType array) {
            collect(array.getGenericComponentType(), into);
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                collect(bound, into);
            }
        } else if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                collect(bound, into);
            }
        }
    }

    /**
     * The top-level classes of one package, read from wherever that package physically lives — a directory
     * for this module's own classes, a jar for {@code bootui-core}'s. Deliberately not a classpath-scanning
     * library: the point of this test is to see the shipped artifact exactly as it is.
     */
    private static Set<Class<?>> classesIn(String packageName) throws IOException, URISyntaxException {
        Set<Class<?>> classes = new TreeSet<>(Comparator.comparing(Class::getName));
        String resourcePath = packageName.replace('.', '/');
        Enumeration<URL> locations =
                BootUiSerdeImportsTest.class.getClassLoader().getResources(resourcePath);
        while (locations.hasMoreElements()) {
            URL location = locations.nextElement();
            if ("file".equals(location.getProtocol())) {
                addFromDirectory(Path.of(location.toURI()), packageName, classes);
            } else if ("jar".equals(location.getProtocol())) {
                addFromJar(location, resourcePath, classes);
            }
        }
        assertThat(classes).as("classes found in %s", packageName).isNotEmpty();
        return classes;
    }

    private static void addFromDirectory(Path directory, String packageName, Set<Class<?>> into) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".class") && !name.contains("$")) {
                    into.add(load(packageName + "." + name.substring(0, name.length() - ".class".length())));
                }
            }
        }
    }

    private static void addFromJar(URL location, String resourcePath, Set<Class<?>> into) throws IOException {
        String path = location.getPath();
        String jar = path.substring("file:".length(), path.indexOf('!'));
        try (JarFile archive = new JarFile(jar)) {
            for (var entry : Collections.list(archive.entries())) {
                String name = entry.getName();
                boolean topLevelClassInPackage = name.startsWith(resourcePath + "/")
                        && name.endsWith(".class")
                        && !name.contains("$")
                        && name.indexOf('/', resourcePath.length() + 1) < 0;
                if (topLevelClassInPackage) {
                    into.add(load(
                            name.substring(0, name.length() - ".class".length()).replace('/', '.')));
                }
            }
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Class listed on the classpath but not loadable: " + name, ex);
        }
    }
}
