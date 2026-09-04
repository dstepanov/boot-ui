package io.github.jdubois.bootui.micronaut.vulnerabilities;

import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Builds the application's dependency inventory by reading the Maven coordinates each jar carries.
 *
 * <p>This is the Micronaut analogue of the Quarkus adapter's build-time application-model capture and the
 * Spring adapter's {@code DependencyCatalog}, but it needs neither a build step nor Spring's resource
 * resolver: a jar published to Maven Central embeds its own coordinate under
 * {@code META-INF/maven/<group>/<artifact>/}, so the <em>actual runtime classpath</em> is the inventory.
 * That is arguably the most honest source available — it describes what the JVM really loaded, including
 * anything a build tool resolved transitively.
 *
 * <p>The coordinate is read from {@code pom.properties} where there is one, and from the {@code pom.xml}
 * beside it where there is not. Both files matter: Maven writes the properties file, Gradle writes only the
 * POM, and Micronaut itself is published from Gradle — so a properties-only reader hands a Micronaut
 * application an inventory that omits Micronaut.
 *
 * <p>"The runtime classpath" is deliberately read from <em>two</em> sources, because neither alone is
 * reliable:
 *
 * <ul>
 *   <li>{@code java.class.path} is the classpath of the JVM <em>process</em>. It is correct for an ordinary
 *       {@code java -cp ... Application} launch, but an in-process launcher hides the application behind
 *       its own: run under {@code mvn exec:java} (which Micronaut projects commonly do, and which this
 *       repository's sample app documented for a long time) and it holds only the Maven launcher jar, so
 *       the inventory used to come back with a single entry — {@code org.apache.maven.wrapper:maven-wrapper}
 *       — which is worse than empty because it is confidently wrong.
 *   <li>The application classloader is what actually loaded the application's classes, so it sees the real
 *       dependency set in exactly those in-process-launcher cases. It is read two ways: the URLs of every
 *       {@link URLClassLoader} in the parent chain (Maven's {@code exec:java} realm, and any custom launcher
 *       built the same way), and {@code getResources("META-INF/maven/")}, which reaches jars behind
 *       classloaders that are not URL-based but do enumerate directory entries.
 * </ul>
 *
 * <p>The two sources are merged and de-duplicated on {@code group:artifact:version}, so an entry seen by
 * both is reported once. Neither source is authoritative over the other: whichever sees a jar first names
 * it, and the coordinate comes from the jar's own Maven metadata either way, so there is nothing to
 * disagree about.
 *
 * <p>The scan is bounded and lazy: it runs once, on first use; it only ever opens jar files; it visits each
 * jar path at most once however many sources point at it; and it skips anything it cannot read. A jar
 * without Maven metadata (a shaded uber-jar, a directory on the classpath) contributes nothing rather than a
 * guessed coordinate — a wrong coordinate would produce wrong vulnerability findings, which is worse than a
 * missing one.
 */
public final class MicronautDependencyProvider implements DependencyProvider {

    static final String SOURCE = "runtime classpath";

    private static final String MAVEN_PREFIX = "META-INF/maven/";

    private static final String POM_PROPERTIES = "pom.properties";

    private static final String POM_XML = "pom.xml";

    /**
     * How deep to walk an exploded {@code META-INF/maven} directory. Real layouts are
     * {@code META-INF/maven/<group>/<artifact>/pom.properties}, i.e. three levels below the directory the
     * classloader hands back; the bound keeps a pathological directory tree from turning the panel into a
     * filesystem crawl.
     */
    private static final int MAVEN_DIRECTORY_DEPTH = 3;

    private final ClassLoader classLoader;

    private volatile List<DependencyDto> cached;

    public MicronautDependencyProvider() {
        this(defaultClassLoader());
    }

    /**
     * Visible for testing: lets a test drive the scan from a classloader it controls, which is the only way
     * to prove the classloader half works when {@code java.class.path} does not carry the jar.
     */
    MicronautDependencyProvider(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Prefers the thread context classloader, because that is the one an in-process launcher swaps in for
     * the application (Maven's {@code exec:java} sets it to the project realm). Falls back to this class's
     * own loader, which is the adapter jar's, when there is no context loader.
     */
    private static ClassLoader defaultClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : MicronautDependencyProvider.class.getClassLoader();
    }

    @Override
    public List<DependencyDto> dependencies() {
        List<DependencyDto> snapshot = cached;
        if (snapshot == null) {
            snapshot = scan();
            cached = snapshot;
        }
        return snapshot;
    }

    private List<DependencyDto> scan() {
        Map<String, DependencyDto> dependencies = new LinkedHashMap<>();
        Set<String> visitedJars = new LinkedHashSet<>();
        for (String jar : classPathJars()) {
            readJar(jar, visitedJars, dependencies);
        }
        for (URL url : classLoaderUrls()) {
            readClassLoaderUrl(url, visitedJars, dependencies);
        }
        for (URL url : mavenDirectoryResources()) {
            readClassLoaderUrl(url, visitedJars, dependencies);
        }
        return dependencies.values().stream()
                .sorted(Comparator.comparing(DependencyDto::packageName).thenComparing(DependencyDto::version))
                .toList();
    }

    private static List<String> classPathJars() {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            return List.of();
        }
        return Stream.of(classpath.split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> entry != null && !entry.isBlank() && entry.endsWith(".jar"))
                .toList();
    }

    /**
     * Every {@link URLClassLoader} in the loader's parent chain, flattened to its URLs. Maven's
     * {@code exec:java} runs the application in exactly such a realm, and it is the direct answer to the
     * classpath {@code java.class.path} does not describe. Loaders that are not URL-based (the JDK
     * application loader on Java 9+, Quarkus-style custom loaders) contribute nothing here and are covered
     * by {@link #mavenDirectoryResources()} instead.
     */
    private List<URL> classLoaderUrls() {
        List<URL> urls = new ArrayList<>();
        for (ClassLoader loader = classLoader; loader != null; loader = parentOf(loader)) {
            if (loader instanceof URLClassLoader urlClassLoader) {
                URL[] loaderUrls = urlClassLoader.getURLs();
                if (loaderUrls != null) {
                    for (URL url : loaderUrls) {
                        if (url != null) {
                            urls.add(url);
                        }
                    }
                }
            }
        }
        return urls;
    }

    private static ClassLoader parentOf(ClassLoader loader) {
        try {
            return loader.getParent();
        } catch (SecurityException ex) {
            return null;
        }
    }

    /**
     * The {@code META-INF/maven/} directory entry as every classloader in the chain sees it. A jar that
     * writes directory entries (Maven's own {@code maven-jar-plugin} does) answers here even when its
     * loader is not a {@link URLClassLoader}, and an exploded build output directory answers with a plain
     * {@code file:} URL.
     */
    private List<URL> mavenDirectoryResources() {
        if (classLoader == null) {
            return List.of();
        }
        try {
            Enumeration<URL> resources = classLoader.getResources(MAVEN_PREFIX);
            List<URL> urls = new ArrayList<>();
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if (url != null) {
                    urls.add(url);
                }
            }
            return urls;
        } catch (IOException | RuntimeException ex) {
            // The inventory must never fail the panel; the java.class.path half still stands.
            return List.of();
        }
    }

    /**
     * Resolves one classloader URL to something readable. A {@code jar:...!/...} URL names the jar it lives
     * in, which is then read whole; a {@code file:} URL is either a jar or a directory to walk.
     */
    private static void readClassLoaderUrl(URL url, Set<String> visitedJars, Map<String, DependencyDto> dependencies) {
        String protocol = url.getProtocol();
        if ("jar".equalsIgnoreCase(protocol)) {
            String jar = jarPath(url.getFile());
            if (jar != null) {
                readJar(jar, visitedJars, dependencies);
            }
            return;
        }
        if (!"file".equalsIgnoreCase(protocol)) {
            return;
        }
        Path path = filePath(url);
        if (path == null) {
            return;
        }
        if (Files.isDirectory(path)) {
            readDirectory(path, dependencies);
        } else if (path.getFileName() != null
                && path.getFileName().toString().endsWith(".jar")
                && Files.isRegularFile(path)) {
            readJar(path.toString(), visitedJars, dependencies);
        }
    }

    /**
     * Extracts the containing jar from the file part of a {@code jar:} URL, which is
     * {@code file:/path/to.jar!/META-INF/maven/}. The nested path is dropped: the whole jar is read anyway,
     * so a jar reached through several entries is still opened once.
     */
    private static String jarPath(String jarUrlFile) {
        if (jarUrlFile == null) {
            return null;
        }
        int separator = jarUrlFile.indexOf("!/");
        String location = separator < 0 ? jarUrlFile : jarUrlFile.substring(0, separator);
        try {
            Path path = filePath(URI.create(location).toURL());
            return path == null ? null : path.toString();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** Converts a {@code file:} URL to a path, tolerating the percent-encoding a URL may carry. */
    private static Path filePath(URL url) {
        try {
            return Path.of(url.toURI());
        } catch (RuntimeException | URISyntaxException ex) {
            try {
                return Path.of(URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8));
            } catch (RuntimeException nested) {
                return null;
            }
        }
    }

    /**
     * Reads every {@code pom.properties} in one jar, at most once per jar path however many classpath
     * sources point at it.
     */
    private static void readJar(String jarPath, Set<String> visitedJars, Map<String, DependencyDto> dependencies) {
        if (!visitedJars.add(jarPath)) {
            return;
        }
        readCoordinates(jarPath, dependencies);
    }

    /**
     * Reads the Maven coordinates from one jar. A jar can legitimately contain several (a shaded artifact),
     * so every {@code META-INF/maven/<group>/<artifact>/} directory it carries is read — and each is read
     * from {@code pom.properties} when there is one, falling back to the {@code pom.xml} beside it
     * otherwise. The fallback is not academic on Micronaut: Micronaut's own jars are published from Gradle,
     * which writes the {@code pom.xml} but no {@code pom.properties}, so without it the inventory of a
     * Micronaut application is missing exactly the framework it is running on.
     */
    private static void readCoordinates(String jarPath, Map<String, DependencyDto> dependencies) {
        try (ZipFile jar = new ZipFile(jarPath)) {
            Map<String, ZipEntry> properties = new LinkedHashMap<>();
            Map<String, ZipEntry> poms = new LinkedHashMap<>();
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(MAVEN_PREFIX)) {
                    continue;
                }
                if (name.endsWith("/" + POM_PROPERTIES)) {
                    properties.put(parentPath(name), entry);
                } else if (name.endsWith("/" + POM_XML)) {
                    poms.put(parentPath(name), entry);
                }
            }
            for (Map.Entry<String, ZipEntry> entry : properties.entrySet()) {
                try (InputStream in = jar.getInputStream(entry.getValue())) {
                    put(readPomProperties(in), dependencies);
                }
            }
            for (Map.Entry<String, ZipEntry> entry : poms.entrySet()) {
                if (properties.containsKey(entry.getKey())) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry.getValue())) {
                    put(readPomXml(in, entry.getKey()), dependencies);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // A jar that cannot be opened contributes nothing; the inventory must never fail the panel.
        }
    }

    /**
     * Reads an exploded {@code META-INF/maven} tree — a build output directory, or an unpacked distribution
     * layout. The walk is depth-bounded to the real layout's depth, and applies the same
     * {@code pom.properties}-then-{@code pom.xml} preference as the jar scan.
     */
    private static void readDirectory(Path mavenDirectory, Map<String, DependencyDto> dependencies) {
        try (Stream<Path> paths = Files.walk(mavenDirectory, MAVEN_DIRECTORY_DEPTH)) {
            List<Path> coordinateDirectories = paths.filter(Files::isDirectory).toList();
            for (Path directory : coordinateDirectories) {
                Path properties = directory.resolve(POM_PROPERTIES);
                if (Files.isRegularFile(properties)) {
                    try (InputStream in = Files.newInputStream(properties)) {
                        put(readPomProperties(in), dependencies);
                        continue;
                    } catch (IOException | RuntimeException ex) {
                        // Fall through to the pom.xml beside it.
                    }
                }
                Path pom = directory.resolve(POM_XML);
                if (Files.isRegularFile(pom)) {
                    try (InputStream in = Files.newInputStream(pom)) {
                        put(readPomXml(in, mavenDirectory.relativize(directory).toString()), dependencies);
                    } catch (IOException | RuntimeException ex) {
                        // One unreadable file must not stop the walk.
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            // A directory that cannot be walked contributes nothing.
        }
    }

    /** The directory part of a zip entry name, or the empty string when there is none. */
    private static String parentPath(String entryName) {
        int lastSlash = entryName.lastIndexOf('/');
        return lastSlash < 0 ? "" : entryName.substring(0, lastSlash);
    }

    private static void put(DependencyDto dependency, Map<String, DependencyDto> dependencies) {
        if (dependency != null) {
            dependencies.putIfAbsent(dependency.packageName() + ":" + dependency.version(), dependency);
        }
    }

    private static DependencyDto readPomProperties(InputStream in) {
        try {
            Properties properties = new Properties();
            properties.load(in);
            String groupId = trimmed(properties.getProperty("groupId"));
            String artifactId = trimmed(properties.getProperty("artifactId"));
            String version = trimmed(properties.getProperty("version"));
            return dependency(groupId, artifactId, version);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Reads a coordinate from a bundled {@code pom.xml}, the fallback for a jar published without a
     * {@code pom.properties}. The group and artifact come from the layout — the enclosing directory is
     * {@code META-INF/maven/<groupId>/<artifactId>}, which is the layout's own promise and needs no parsing
     * — and only the version is taken from the document, falling back to the parent's when the module
     * inherits it. A version still carrying an unresolved {@code ${...}} placeholder is rejected: a wrong
     * version produces wrong vulnerability findings, which is worse than a missing dependency.
     *
     * <p>The parser is locked down the same way the Spring adapter's {@code DependencyCatalog} locks its
     * own down — no DOCTYPE, no entity resolution, no external DTD or schema access — because it is reading
     * a file that arrived inside a third-party jar.
     */
    private static DependencyDto readPomXml(InputStream in, String coordinateDirectory) {
        String[] segments = coordinateDirectory.split("[/\\\\]");
        if (segments.length < 2) {
            return null;
        }
        String groupId = trimmed(segments[segments.length - 2]);
        String artifactId = trimmed(segments[segments.length - 1]);
        if (groupId == null || artifactId == null) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element project = factory.newDocumentBuilder().parse(in).getDocumentElement();
            String version = childText(project, "version");
            if (version == null) {
                Element parent = child(project, "parent");
                version = parent == null ? null : childText(parent, "version");
            }
            if (version == null || version.contains("${")) {
                return null;
            }
            return dependency(groupId, artifactId, version);
        } catch (IOException | ParserConfigurationException | SAXException | RuntimeException ex) {
            return null;
        }
    }

    private static String childText(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : trimmed(child.getTextContent());
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element
                    && (name.equals(element.getLocalName()) || name.equals(element.getNodeName()))) {
                return element;
            }
        }
        return null;
    }

    private static DependencyDto dependency(String groupId, String artifactId, String version) {
        if (groupId == null || artifactId == null || version == null) {
            return null;
        }
        return new DependencyDto(
                groupId, artifactId, version, groupId + ":" + artifactId, SOURCE, 0, "NONE", List.of());
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
