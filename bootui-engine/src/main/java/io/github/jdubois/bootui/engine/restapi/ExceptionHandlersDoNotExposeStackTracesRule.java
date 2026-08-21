package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import java.util.ArrayList;
import java.util.List;

/**
 * RAPI-ERR-011 — a declared exception handler reads or prints a stack trace, which is the usual route for
 * internal class names, file paths, and framework internals to reach an error response or a client-visible
 * log line.
 *
 * <p>The evidence is a declared call in the handler's own bytecode to a stack-trace accessor
 * ({@code printStackTrace}, {@code getStackTrace}, {@code fillInStackTrace}, or a
 * {@code getStackFrames}/{@code getFullStackTrace} helper). Nothing is executed and no captured response,
 * message, or stack trace is ever included in the report — only the handler's own name.</p>
 */
final class ExceptionHandlersDoNotExposeStackTracesRule extends AbstractRestApiRule {

    ExceptionHandlersDoNotExposeStackTracesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-011",
                "Exception handlers do not expose stack traces",
                RestApiCategory.ERROR_HANDLING,
                "HIGH",
                "An exception handler reads or prints a stack trace, which commonly leaks internal class names,"
                        + " file paths, and framework internals into an error response.",
                "Log the failure with a correlation identifier through the logging framework and return only a"
                        + " stable, non-revealing error representation to the client.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (ExceptionHandlerModel handler : context.exceptionHandlers()) {
            String name = simpleName(handler.declaringClassName()) + "#" + handler.methodName();
            if (handler.printsStackTrace()) {
                violations.add(name + " prints the exception stack trace");
            } else if (handler.readsStackTrace() && handler.rendersBody()) {
                violations.add(name + " reads the exception stack trace while rendering a response body");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}
