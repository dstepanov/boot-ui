package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ThrownExceptionModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAPI-ERR-009 — an endpoint declares an application exception in its {@code throws} clause that no
 * declared handler maps, so the failure falls through to the framework default instead of the
 * application's own error contract.
 *
 * <p>The finding is evidence-backed on both sides: the exception type comes from a declared {@code throws}
 * clause, and coverage is decided against the declared {@code @ExceptionHandler}/{@code ExceptionMapper}
 * types, including supertypes, plus {@code @ResponseStatus}-annotated exceptions (which Spring maps without
 * a handler). It never claims a mapping is missing merely because no failure has been observed, and it
 * stays silent when the application declares no handlers at all — {@code RAPI-ERR-001} already reports
 * that.
 */
final class DeclaredExceptionsHaveHandlersRule extends AbstractRestApiRule {

    DeclaredExceptionsHaveHandlersRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-009",
                "Declared exceptions have a declared handler",
                RestApiCategory.ERROR_HANDLING,
                "MEDIUM",
                "An endpoint declares an application exception that no @ExceptionHandler, exception mapper, or"
                        + " @ResponseStatus maps, so it falls through to the framework's default error response"
                        + " instead of the application's error contract.",
                "Map the exception with an @ExceptionHandler/exception mapper, or annotate it with"
                        + " @ResponseStatus, so its HTTP status and body are part of the declared contract.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.exceptionHandlers().isEmpty() || context.thrownExceptions().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        Set<String> mapped = new LinkedHashSet<>(context.responseStatusExceptionClasses());
        for (ExceptionHandlerModel handler : context.exceptionHandlers()) {
            mapped.addAll(handler.handledExceptionTypes());
        }
        List<String> violations = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        for (ThrownExceptionModel thrown : context.thrownExceptions()) {
            if (isMapped(thrown, mapped)) {
                continue;
            }
            String violation = thrown.controllerSimpleName() + "#" + thrown.methodName() + " declares "
                    + thrown.exceptionSimpleName() + ", which no declared handler maps";
            if (reported.add(violation)) {
                violations.add(violation);
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    /** A type is mapped when it, or any of its ancestors, is declared as handled. */
    private static boolean isMapped(ThrownExceptionModel thrown, Set<String> mapped) {
        if (mapped.contains(thrown.exceptionTypeName())) {
            return true;
        }
        for (String ancestor : thrown.exceptionSuperTypeNames()) {
            if (mapped.contains(ancestor)) {
                return true;
            }
        }
        return false;
    }
}
