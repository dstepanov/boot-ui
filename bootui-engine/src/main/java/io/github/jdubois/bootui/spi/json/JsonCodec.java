package io.github.jdubois.bootui.spi.json;

import java.io.IOException;

/**
 * Adapter-supplied JSON binding for the engine's two outbound HTTP clients (the GitHub dashboard client and
 * the OSV vulnerability scanner), keeping the engine free of any Jackson dependency.
 *
 * <p>Spring Boot implements it over Jackson 3 ({@code tools.jackson}); Quarkus and Micronaut over Jackson 2
 * ({@code com.fasterxml.jackson}). Both generations must produce identical {@link JsonTree} accessor results
 * so the same engine client renders the same payload on every stack — that parity is what the adapters'
 * codec tests pin.</p>
 *
 * <p>Both methods report failure as a plain {@link IOException} rather than a library-specific exception:
 * Jackson 2's {@code JsonProcessingException} already extends {@code IOException} while Jackson 3's
 * {@code JacksonException} is unchecked, so wrapping at this boundary is what lets the engine handle a
 * malformed third-party response with the same bounded, fail-closed error path it uses for a network failure
 * instead of letting an unchecked parser exception escape on one stack only.</p>
 */
public interface JsonCodec {

    /**
     * Parses a whole JSON document into a neutral tree.
     *
     * @throws IOException when the payload is not well-formed JSON
     */
    JsonTree read(String json) throws IOException;

    /**
     * Serializes a request body built from neutral values only: {@link java.util.Map} (rendered as a JSON
     * object, in iteration order), {@link java.util.List}, {@link String}, {@link Number}, {@link Boolean},
     * and {@code null}. No adapter binding, annotation, or reflection over application types is involved.
     *
     * @throws IOException when the value cannot be serialized
     */
    String write(Object value) throws IOException;
}
