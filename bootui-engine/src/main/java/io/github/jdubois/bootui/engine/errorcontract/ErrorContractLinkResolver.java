package io.github.jdubois.bootui.engine.errorcontract;

import io.github.jdubois.bootui.core.dto.ErrorContractLinkDto;

/**
 * Seam the Exceptions panel uses to attach a declared-handler cross-link to a retained failure, without
 * the Exceptions feature depending on how the error contract is discovered.
 *
 * <p>Adapters wire {@code ErrorContractService} here. When no error-contract backend exists the seam is
 * simply absent and every exception group stays unlinked, which is the honest state rather than a
 * degraded guess.</p>
 */
@FunctionalInterface
public interface ErrorContractLinkResolver {

    /**
     * @param exceptionClassName fully-qualified type of the retained exception
     * @param handlerEvidence the retained request handler, or {@code null} when the failure carried none
     * @return the single safely attributable declaration, or {@code null} when the evidence is missing,
     *     ambiguous, or matches nothing
     */
    ErrorContractLinkDto resolve(String exceptionClassName, String handlerEvidence);
}
