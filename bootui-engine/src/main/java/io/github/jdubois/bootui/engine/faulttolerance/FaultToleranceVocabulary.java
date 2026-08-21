package io.github.jdubois.bootui.engine.faulttolerance;

/**
 * The neutral vocabulary the Fault Tolerance contract uses, shared by the engine, every adapter provider and
 * the Live Activity mapping so the three supported libraries render through one stable shape.
 */
public final class FaultToleranceVocabulary {

    /** Provider ids. */
    public static final String PROVIDER_RESILIENCE4J = "resilience4j";

    public static final String PROVIDER_SPRING_RETRY = "spring-retry";
    public static final String PROVIDER_SMALLRYE_FAULT_TOLERANCE = "smallrye-fault-tolerance";

    /** Policy types, in the contract's stable display order. */
    public static final String TYPE_CIRCUIT_BREAKER = "CIRCUIT_BREAKER";

    public static final String TYPE_RETRY = "RETRY";
    public static final String TYPE_RATE_LIMITER = "RATE_LIMITER";
    public static final String TYPE_BULKHEAD = "BULKHEAD";
    public static final String TYPE_TIME_LIMITER = "TIME_LIMITER";
    public static final String TYPE_FALLBACK = "FALLBACK";

    /** Discovery sources. */
    public static final String SOURCE_REGISTRY = "REGISTRY";

    public static final String SOURCE_ANNOTATION = "ANNOTATION";
    public static final String SOURCE_CONFIGURATION = "CONFIGURATION";

    /** Setting provenance. */
    public static final String PROVENANCE_DEFAULT = "DEFAULT";

    public static final String PROVENANCE_CONFIGURED = "CONFIGURED";
    public static final String PROVENANCE_UNKNOWN = "UNKNOWN";

    /** Circuit-breaker states. {@code UNKNOWN} is used when a library exposes no state at all. */
    public static final String STATE_CLOSED = "CLOSED";

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_HALF_OPEN = "HALF_OPEN";
    public static final String STATE_DISABLED = "DISABLED";
    public static final String STATE_FORCED_OPEN = "FORCED_OPEN";
    public static final String STATE_UNKNOWN = "UNKNOWN";

    /** Event outcomes. */
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    public static final String OUTCOME_ERROR = "ERROR";
    public static final String OUTCOME_RETRY = "RETRY";
    public static final String OUTCOME_RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_TIMEOUT = "TIMEOUT";
    public static final String OUTCOME_SHORT_CIRCUITED = "SHORT_CIRCUITED";
    public static final String OUTCOME_STATE_TRANSITION = "STATE_TRANSITION";
    public static final String OUTCOME_FALLBACK = "FALLBACK";

    private FaultToleranceVocabulary() {}

    /**
     * Rank of a policy type in the contract's display order. An unrecognized type sorts last rather than
     * being rejected, so a future adapter type still renders deterministically.
     */
    public static int typeRank(String type) {
        if (type == null) {
            return Integer.MAX_VALUE;
        }
        return switch (type) {
            case TYPE_CIRCUIT_BREAKER -> 0;
            case TYPE_RETRY -> 1;
            case TYPE_RATE_LIMITER -> 2;
            case TYPE_BULKHEAD -> 3;
            case TYPE_TIME_LIMITER -> 4;
            case TYPE_FALLBACK -> 5;
            default -> Integer.MAX_VALUE;
        };
    }

    /** Whether an outcome represents a protection that actually kicked in (rendered as a warning). */
    public static boolean isProtectiveOutcome(String outcome) {
        if (outcome == null) {
            return false;
        }
        return switch (outcome) {
            case OUTCOME_RETRY, OUTCOME_REJECTED, OUTCOME_SHORT_CIRCUITED, OUTCOME_FALLBACK, OUTCOME_STATE_TRANSITION ->
                true;
            default -> false;
        };
    }

    /** Whether an outcome represents a failure the user should treat as an error. */
    public static boolean isFailureOutcome(String outcome) {
        if (outcome == null) {
            return false;
        }
        return switch (outcome) {
            case OUTCOME_ERROR, OUTCOME_RETRY_EXHAUSTED, OUTCOME_TIMEOUT -> true;
            default -> false;
        };
    }

    /**
     * Categorises a captured failure by its exception's simple class name.
     *
     * <p>The exception message is deliberately never retained: application exception messages routinely carry
     * user data, identifiers or credentials, and the fault tolerance panel is a metadata-only view.</p>
     */
    public static String failureCategory(Throwable throwable) {
        return throwable == null ? null : throwable.getClass().getSimpleName();
    }
}
