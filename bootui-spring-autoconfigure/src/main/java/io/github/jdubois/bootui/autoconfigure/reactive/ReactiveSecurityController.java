package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.security.ReactiveSecurityScanner;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import java.time.Clock;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.env.Environment;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Serves the Spring Security Advisor panel on the Spring WebFlux (reactive) adapter.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan}
 * introspects the registered {@code SecurityWebFilterChain} beans (via
 * {@code WebFilterChainProxy}) and related security beans, and evaluates a bounded,
 * static best-practice ruleset against the host application's reactive security configuration.</p>
 *
 * <p>This controller is the reactive counterpart of the servlet-only
 * {@code io.github.jdubois.bootui.autoconfigure.security.SecurityController}: it reuses the same
 * {@link SecurityReport} DTO and the same {@link DismissedRulesStore}, so the browser UI and
 * dismissal store see an identical shape on both stacks.</p>
 */
@RestController
@ConditionalOnClass(WebFilterChainProxy.class)
@RequestMapping("/bootui/api/security")
public class ReactiveSecurityController {

    private final ReactiveSecurityScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private volatile SecurityReport lastReport;

    @Autowired
    public ReactiveSecurityController(
            ObjectProvider<WebFilterChainProxy> chainProxies,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment,
            DismissedRulesStore dismissedRules) {
        this(new ReactiveSecurityScanner(chainProxies, beanFactories, environment, Clock.systemUTC()), dismissedRules);
    }

    ReactiveSecurityController(ReactiveSecurityScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public SecurityReport security() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    /**
     * Runs the security scan off the event loop (on {@code boundedElastic}) because the scan
     * uses reflection and may call {@code Flux.collectList().block(timeout)} internally.
     */
    @PostMapping("/scan")
    public Mono<SecurityReport> scan() {
        return Mono.fromCallable(() -> {
                    SecurityReport report = scanner.scan();
                    lastReport = report;
                    return scanner.applyDismissals(report, dismissedRules.load());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
