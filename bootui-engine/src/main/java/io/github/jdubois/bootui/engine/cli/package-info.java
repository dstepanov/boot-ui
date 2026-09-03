/**
 * Framework-neutral policy for the {@code /bootui/api/cli} facade: the REST projection of the MCP tool
 * registry that the {@code bootui} command-line client talks to.
 *
 * <p>The facade deliberately re-uses {@code McpDispatcher} rather than re-implementing any gate, so panel
 * enable/read-only policy, argument normalization, result capping, concurrency, and timeouts behave
 * identically whether a tool is reached from an MCP agent or from a terminal. What lives here is only the
 * part MCP does not have an answer for: how a dispatch outcome becomes an HTTP status and body, expressed
 * without depending on Spring, Quarkus, or a JSON library so all three adapters answer identically.
 */
package io.github.jdubois.bootui.engine.cli;
