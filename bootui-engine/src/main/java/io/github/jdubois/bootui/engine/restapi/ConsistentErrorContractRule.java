package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.errorcontract.ErrorBodyCategory;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * RAPI-ERR-010 — the declared exception handlers disagree on the shape of an error response, so a client
 * has to handle several error formats from one API.
 *
 * <p>Both signals are read from declarations only: the response-body category derived from each handler's
 * declared return type, and the error media types each handler declares. The rule stays silent unless at
 * least two body-rendering handlers exist and they genuinely disagree, and it never reports the content of
 * a response.</p>
 */
final class ConsistentErrorContractRule extends AbstractRestApiRule {

    ConsistentErrorContractRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-010",
                "Error responses share one contract",
                RestApiCategory.ERROR_HANDLING,
                "LOW",
                "Declared exception handlers return different error body shapes or media types, so clients must"
                        + " parse several error formats from the same API.",
                "Return one error representation — ideally an RFC 9457 problem-details document — from every"
                        + " exception handler, and declare the same error media type.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<ExceptionHandlerModel> rendering = context.exceptionHandlers().stream()
                .filter(ExceptionHandlerModel::rendersBody)
                .filter(handler -> !handler.returnsVoid())
                .toList();
        if (rendering.size() < 2) {
            return RestApiRuleSupport.pass(definition());
        }

        List<String> violations = new ArrayList<>();
        Map<String, Set<String>> byCategory = new LinkedHashMap<>();
        for (ExceptionHandlerModel handler : rendering) {
            byCategory
                    .computeIfAbsent(bodyCategory(handler), ignored -> new TreeSet<>())
                    .add(simpleName(handler.declaringClassName()) + "#" + handler.methodName());
        }
        if (byCategory.size() > 1) {
            List<String> parts = new ArrayList<>();
            byCategory.forEach((category, handlers) -> parts.add(category + " (" + String.join(", ", handlers) + ")"));
            violations.add("Exception handlers return " + byCategory.size() + " different error body shapes: "
                    + String.join("; ", parts));
        }

        // Compare each handler's declared set, not the union: two handlers that both declare
        // {application/json, application/xml} agree on one contract.
        Set<String> declaredSets = new TreeSet<>();
        for (ExceptionHandlerModel handler : rendering) {
            if (!handler.produces().isEmpty()) {
                declaredSets.add(String.join(", ", new TreeSet<>(handler.produces())));
            }
        }
        if (declaredSets.size() > 1) {
            violations.add("Exception handlers declare " + declaredSets.size() + " different error media types: "
                    + String.join("; ", declaredSets));
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    /**
     * The declared body shape of a handler, kept deliberately coarse so a rename or a package move cannot
     * turn one contract into two. Classification is delegated to {@link ErrorBodyCategory} so this rule and
     * the REST API panel's error-contract catalogue can never disagree.
     */
    private static String bodyCategory(ExceptionHandlerModel handler) {
        if (handler.returnsProblemType()) {
            return "problem details";
        }
        String category = ErrorBodyCategory.classify(
                handler.returnsVoid() ? "void" : null, handler.bodyTypeName(), handler.returnsResponseEntity());
        return switch (category) {
            case ErrorBodyCategory.PROBLEM_DETAIL -> "problem details";
            case ErrorBodyCategory.STRING -> "raw string";
            case ErrorBodyCategory.DYNAMIC -> "untyped map/object";
            case ErrorBodyCategory.EMPTY -> "no body";
            case ErrorBodyCategory.CUSTOM_OBJECT -> "custom object (" + simpleName(handler.bodyTypeName()) + ")";
            default -> "unresolved";
        };
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}
