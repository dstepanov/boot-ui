package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * One Oracle sequence's consumption, including one created implicitly to back a {@code GENERATED ... AS
 * IDENTITY} column.
 *
 * <p>Unlike PostgreSQL, there is no separate "owning column capacity" to cross-reference: Oracle has a single
 * generic {@code NUMBER} type (up to 38 digits) for every integer identifier, so a sequence's own
 * {@code max_value} is always the binding ceiling.</p>
 *
 * <p>{@code all_sequences.last_number} is the <em>next</em> value the sequence will hand out if its cache is
 * exhausted — with caching (the default), it already reflects the high-water mark reserved in the cache, not
 * merely what has been consumed by a committed {@code nextval}. That makes this a conservative (slightly
 * pessimistic, never optimistic) measurement: it can report a sequence as further along than rows actually
 * committed so far account for, but never further behind — so a warning here is never a false alarm caused by
 * caching, only possibly an early one.</p>
 *
 * @param minValue {@code all_sequences.min_value}
 * @param maxValue {@code all_sequences.max_value}
 * @param incrementBy {@code all_sequences.increment_by}
 * @param cycle {@code all_sequences.cycle_flag = 'Y'}
 * @param excluded {@code true} for a session, scalable, or sharded sequence ({@code session_flag}/
 *     {@code scale_flag}/{@code sharded_flag}): each has range/reset semantics a plain percent-of-max-value
 *     reading would misrepresent, so this is never a finding
 */
record OracleSequenceUsage(
        String schema,
        String sequence,
        BigInteger lastNumber,
        BigInteger maxValue,
        BigInteger minValue,
        BigInteger incrementBy,
        boolean cycle,
        boolean excluded) {

    String qualifiedName() {
        return schema == null || schema.isBlank() ? sequence : schema + "." + sequence;
    }

    /** Percentage of the sequence's own range consumed, computed in arbitrary precision so it cannot overflow. */
    int percentUsed() {
        if (lastNumber == null || maxValue == null || maxValue.signum() <= 0) {
            return 0;
        }
        BigInteger floor = minValue == null ? BigInteger.ZERO : minValue;
        BigInteger range = maxValue.subtract(floor);
        if (range.signum() <= 0) {
            return 0;
        }
        BigInteger consumed = lastNumber.subtract(floor);
        if (consumed.signum() < 0) {
            return 0;
        }
        BigDecimal percent = new BigDecimal(consumed)
                .multiply(BigDecimal.valueOf(100))
                .divide(new BigDecimal(range), 0, RoundingMode.DOWN);
        return percent.min(BigDecimal.valueOf(100)).intValue();
    }
}
