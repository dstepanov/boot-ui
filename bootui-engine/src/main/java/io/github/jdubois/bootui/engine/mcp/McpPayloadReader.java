package io.github.jdubois.bootui.engine.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Reads an MCP request stream without ever buffering more than the configured payload limit. */
public final class McpPayloadReader {

    private static final int BUFFER_SIZE = 8192;

    private McpPayloadReader() {}

    public static byte[] read(InputStream input, int maxPayloadBytes) {
        Objects.requireNonNull(input, "input");
        int limit = Math.max(1, maxPayloadBytes);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, BUFFER_SIZE));
            byte[] buffer = new byte[Math.min(limit, BUFFER_SIZE)];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > limit - total) {
                    throw new PayloadTooLargeException();
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read request payload", ex);
        }
    }

    /** Raised as soon as reading another chunk would exceed the configured request budget. */
    public static final class PayloadTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
