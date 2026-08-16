package io.github.jdubois.bootui.engine.databaseadvisor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The wall-clock budget one scan runs under, shared by every datasource in that scan.
 *
 * <p>It is checked between units of work (per datasource, per table, before each catalog augmentation)
 * rather than interrupting a running JDBC call: the introspector never spawns a thread, so the only two
 * honest ways to bound it are stopping between steps and {@code Statement.setQueryTimeout} on the statements
 * it does own. Whatever was read before the budget ran out is kept, and the scan reports the truncation.</p>
 */
final class ScanBudget {

    private final long deadlineNanos;
    private final LongSupplier nanoTime;

    private ScanBudget(long deadlineNanos, LongSupplier nanoTime) {
        this.deadlineNanos = deadlineNanos;
        this.nanoTime = nanoTime;
    }

    static ScanBudget of(Duration budget) {
        return of(budget, System::nanoTime);
    }

    /** Test seam: a budget driven by an explicit clock instead of {@code System.nanoTime()}. */
    static ScanBudget of(Duration budget, LongSupplier nanoTime) {
        return new ScanBudget(nanoTime.getAsLong() + budget.toNanos(), nanoTime);
    }

    boolean exhausted() {
        return nanoTime.getAsLong() - deadlineNanos >= 0;
    }

    /**
     * The seconds left in the budget, clamped to at least one, for {@code Statement.setQueryTimeout} — so a
     * catalog statement can never be given more time than the whole scan has left.
     */
    int remainingSecondsAtMost(int cap) {
        long remaining = deadlineNanos - nanoTime.getAsLong();
        long seconds = TimeUnit.NANOSECONDS.toSeconds(remaining);
        if (seconds <= 0) {
            return 1;
        }
        return (int) Math.min(cap, seconds);
    }
}
