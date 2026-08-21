package io.github.jdubois.bootui.engine.sqltrace;

/**
 * One captured inbound request, reduced to exactly what route attribution may use.
 *
 * <p>Adapters build these from evidence they already hold — the HTTP exchange buffer, the trace-id
 * registry, and (on a servlet runtime only) the serving-thread registry — so attribution adds no request
 * capture of its own. The record deliberately has no field for a query string, a request body, a header or
 * a path parameter: what is not carried here cannot be grouped on or leaked.</p>
 *
 * @param id identity of the captured request within one report, used only to count distinct requests per
 *     route; build it with {@link #of} so the three adapters cannot drift
 * @param method HTTP method, used as part of the route grouping key
 * @param path the request path with no query string; masked by {@link RoutePathMasker} when no
 *     {@code routeTemplate} is available
 * @param routeTemplate the declared route template such as {@code /api/orders/{id}} when the runtime can
 *     supply one, otherwise {@code null}
 * @param traceId the distributed-trace id captured for the request, or {@code null}
 * @param startMillis start of the request's handling window
 * @param endMillis end of the request's handling window
 * @param thread the worker thread that served the request, or {@code null} on a runtime with no
 *     one-thread-per-request invariant
 */
public record SqlRequestEvidence(
        String id,
        String method,
        String path,
        String routeTemplate,
        String traceId,
        long startMillis,
        long endMillis,
        String thread) {

    /**
     * Evidence identified by its position in the adapter's snapshot.
     *
     * <p>The ordinal is used rather than method, path and start time because two concurrent requests to the
     * same path routinely start in the same millisecond, and an identity that collapsed them would
     * under-count the requests behind a route. It is never serialized, so it exposes nothing.</p>
     */
    public static SqlRequestEvidence of(
            int ordinal,
            String method,
            String path,
            String routeTemplate,
            String traceId,
            long startMillis,
            long endMillis,
            String thread) {
        return new SqlRequestEvidence(
                "request-" + ordinal, method, path, routeTemplate, traceId, startMillis, endMillis, thread);
    }
}
