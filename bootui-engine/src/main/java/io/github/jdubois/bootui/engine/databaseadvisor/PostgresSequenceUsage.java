package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * One PostgreSQL sequence's consumption, measured against the value it can actually reach.
 *
 * <p>That ceiling is {@code min(sequence max_value, owning column capacity)}: a {@code bigint} sequence
 * feeding an {@code integer} column runs out at 2,147,483,647 even though the sequence itself would happily
 * count to 9.2 quintillion — the previous "percentage of {@code pg_sequences.max_value}" reading would have
 * shown 0% right up to the outage. A cycling sequence wraps instead of failing, so it is never a finding.</p>
 *
 * @param lastValue {@code pg_sequences.last_value}
 * @param sequenceMax {@code pg_sequences.max_value}
 * @param columnCapacity the owning column type's maximum, or {@code null} when the owner is unknown
 * @param cycle {@code pg_sequences.cycle}
 * @param incrementBy {@code pg_sequences.increment_by}, or {@code null} when the server did not report it
 */
record PostgresSequenceUsage(
        String schema,
        String sequence,
        BigInteger lastValue,
        BigInteger sequenceMax,
        BigInteger columnCapacity,
        boolean cycle,
        String ownerSchema,
        String ownerTable,
        String ownerColumn,
        String ownerType,
        Long incrementBy) {

    String qualifiedName() {
        return schema == null || schema.isBlank() ? sequence : schema + "." + sequence;
    }

    /** The value this sequence can actually reach before inserts start failing. */
    BigInteger effectiveMax() {
        if (columnCapacity == null) {
            return sequenceMax;
        }
        if (sequenceMax == null) {
            return columnCapacity;
        }
        return sequenceMax.min(columnCapacity);
    }

    /** Whether the effective ceiling comes from the owning column rather than the sequence definition. */
    boolean limitedByColumn() {
        return columnCapacity != null && sequenceMax != null && columnCapacity.compareTo(sequenceMax) < 0;
    }

    String describeOwner() {
        if (ownerTable == null || ownerColumn == null) {
            return "no owning column";
        }
        String table = ownerSchema == null || ownerSchema.isBlank() ? ownerTable : ownerSchema + "." + ownerTable;
        return table + "." + ownerColumn + (ownerType == null ? "" : " (" + ownerType + ")");
    }

    /** Percentage of the effective ceiling consumed, computed in arbitrary precision so it cannot overflow. */
    int percentUsed() {
        BigInteger max = effectiveMax();
        if (lastValue == null || max == null || max.signum() <= 0 || lastValue.signum() < 0) {
            return 0;
        }
        BigDecimal percent = new BigDecimal(lastValue)
                .multiply(BigDecimal.valueOf(100))
                .divide(new BigDecimal(max), 0, RoundingMode.DOWN);
        return percent.min(BigDecimal.valueOf(100)).intValue();
    }
}
