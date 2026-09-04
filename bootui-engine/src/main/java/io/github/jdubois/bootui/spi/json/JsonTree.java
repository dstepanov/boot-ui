package io.github.jdubois.bootui.spi.json;

import java.util.List;
import java.util.Map;

/**
 * Framework- and library-neutral read-only view over a parsed JSON tree node.
 *
 * <p>The engine must stay JSON-library-free: Spring Boot 4 ships Jackson 3 ({@code tools.jackson.*}) while
 * Quarkus and Micronaut ship Jackson 2 ({@code com.fasterxml.jackson.*}) — an incompatible artifact
 * <em>and</em> package, so no single Jackson type can appear in shared code. Engine features that must read a
 * third-party JSON response (the GitHub dashboard client and the OSV vulnerability scanner) therefore navigate
 * responses through this interface, and each adapter supplies a thin wrapper over its own Jackson runtime via
 * {@link JsonCodec}. That is what lets one implementation of those two clients serve all four request stacks
 * instead of one near-identical copy per adapter.</p>
 *
 * <p>Accessor semantics deliberately mirror Jackson's {@code JsonNode} so an adapter wrapper is a direct
 * delegation with no translation logic that could drift between stacks:</p>
 *
 * <ul>
 *   <li>{@link #path} never returns {@code null}; an absent field or out-of-range index yields a node for
 *       which {@link #isMissing()} is {@code true}, and navigating further from it stays missing.</li>
 *   <li>{@link #get} returns {@code null} for an absent field, while a JSON {@code null} value is a non-null
 *       node for which {@link #isNull()} is {@code true}.</li>
 *   <li>The {@code as*} accessors coerce scalars and fall back to the supplied default when the node is not
 *       convertible, exactly like Jackson's defaulting overloads.</li>
 *   <li>Iteration walks an array's elements (an object's values), and is empty for scalars and missing
 *       nodes.</li>
 * </ul>
 *
 * <p>This is a separate contract from the older {@code io.github.jdubois.bootui.spi.agent.AgentJson}, which
 * carries a file/JSONL-oriented parser and a {@code toPrettyString()} raw-reveal accessor that only the agent
 * session store needs. Both exist for the same reason; folding the agent SPI into this one is a follow-up, not
 * a prerequisite.</p>
 */
public interface JsonTree extends Iterable<JsonTree> {

    /** The named child, or a missing node when absent. Never {@code null}. */
    JsonTree path(String fieldName);

    /** The array element at {@code index}, or a missing node when out of range. Never {@code null}. */
    JsonTree path(int index);

    /** The named child, or {@code null} when absent. */
    JsonTree get(String fieldName);

    /** Whether this node stands for an absent field or index rather than a present JSON value. */
    boolean isMissing();

    /** Whether this node is a present JSON {@code null}. */
    boolean isNull();

    boolean isObject();

    boolean isArray();

    boolean isString();

    /** Number of elements for an array (fields for an object), otherwise 0. */
    int size();

    /**
     * The scalar's string form, or the empty string for a container, a missing node, or a JSON {@code null}.
     *
     * <p>The empty-string fallback is specified rather than delegated because the two Jackson generations
     * disagree: Jackson 2's {@code asText()} coerces an object or array to {@code ""}, while Jackson 3's
     * {@code asString()} throws. Callers treat a blank result as "absent", so pinning it here is what keeps a
     * third-party response that puts an object where a string was documented from failing a refresh on one
     * stack and degrading quietly on another.</p>
     */
    String asString();

    boolean asBoolean(boolean defaultValue);

    int asInt(int defaultValue);

    long asLong(long defaultValue);

    /** Object field entries in document order; empty for non-objects. */
    List<Map.Entry<String, JsonTree>> properties();
}
