package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedSequenceGeneratorFacts;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A {@code @GeneratedValue(strategy = SEQUENCE)} attribute whose {@code @SequenceGenerator(allocationSize=N)}
 * disagrees with the physical sequence's actual {@code INCREMENT BY}.
 *
 * <p>This is different from — and not a duplicate of — the Hibernate Advisor's own {@code HIB-ID-003} (which
 * flags {@code allocationSize=1} purely from the mapping, never touching the database): this rule
 * cross-references the declared {@code allocationSize} against the database's actual sequence definition,
 * and only fires on a genuine mismatch between the two, at any allocation size.</p>
 *
 * <p>Hibernate's pooled/pooled-lo optimizers call {@code nextval} once and then hand out {@code
 * allocationSize} consecutive identifiers from that single value in memory, assuming the sequence advances by
 * exactly {@code allocationSize} on every call so the next {@code nextval} resumes exactly where the
 * in-memory block left off. If the physical sequence's {@code INCREMENT BY} is smaller, two JVMs (or two
 * threads racing a fresh {@code nextval}) can compute overlapping identifier blocks — a real duplicate-key or
 * silent-overwrite risk, not merely wasted numbers.</p>
 *
 * <p>Only resolved on both sides: an attribute's {@code @SequenceGenerator} must explicitly name a physical
 * {@code sequenceName} (never guessed), and that name must match exactly one sequence across every readable
 * PostgreSQL or Oracle datasource — an unresolved or ambiguous name is skipped rather than guessed.</p>
 */
final class HibernateSequenceIncrementMismatchRule extends AbstractDatabaseAdvisorRule {

    HibernateSequenceIncrementMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-008",
                "Hibernate sequence allocationSize does not match the physical sequence's INCREMENT BY",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Cross-references @SequenceGenerator(sequenceName=..., allocationSize=...) against the "
                        + "PostgreSQL/Oracle physical sequence's actual INCREMENT BY, when the sequence name "
                        + "resolves unambiguously to exactly one readable datasource.",
                "Set the physical sequence's INCREMENT BY to match allocationSize exactly (ALTER SEQUENCE ... "
                        + "INCREMENT BY ...), or change allocationSize to match the sequence. Hibernate's pooled "
                        + "identifier optimizers assume these two numbers are equal; a mismatch can let two "
                        + "application instances hand out overlapping identifier blocks.",
                "https://vladmihalcea.com/the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate/"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        if (!context.hibernateAvailable()) {
            return skipped("No EntityManagerFactory/Hibernate metamodel is available to cross-reference.");
        }
        List<SchemaSnapshot> schemas = context.availableSchemas();
        if (schemas.isEmpty()) {
            return skipped("No physical schema could be read to cross-reference against.");
        }
        List<String> details = new ArrayList<>();
        for (MappedEntityFacts entity : context.hibernateEntities()) {
            for (MappedSequenceGeneratorFacts generator : entity.sequenceGenerators()) {
                checkGenerator(schemas, generator, details);
            }
        }
        return violation(details);
    }

    private void checkGenerator(
            List<SchemaSnapshot> schemas, MappedSequenceGeneratorFacts generator, List<String> details) {
        BigInteger incrementBy = null;
        int matches = 0;
        for (SchemaSnapshot schema : schemas) {
            for (PostgresSequenceUsage sequence :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_SEQUENCES)) {
                if (sameName(sequence.sequence(), generator.sequenceName()) && sequence.incrementBy() != null) {
                    incrementBy = BigInteger.valueOf(sequence.incrementBy());
                    matches++;
                }
            }
            for (OracleSequenceUsage sequence : schema.vendorFindings().findings(VendorFindingKinds.ORACLE_SEQUENCES)) {
                if (sameName(sequence.sequence(), generator.sequenceName()) && sequence.incrementBy() != null) {
                    incrementBy = sequence.incrementBy();
                    matches++;
                }
            }
        }
        if (matches != 1 || incrementBy == null) {
            // Not found, or found in more than one place: resolve reliably or not at all.
            return;
        }
        BigInteger allocationSize = BigInteger.valueOf(generator.allocationSize());
        if (incrementBy.equals(allocationSize)) {
            return;
        }
        details.add(generator.attributeDescription() + " declares @SequenceGenerator(sequenceName=\""
                + generator.sequenceName() + "\", allocationSize=" + generator.allocationSize()
                + "), but the physical sequence's INCREMENT BY is " + incrementBy
                + " — Hibernate's in-memory identifier blocks assume these match.");
    }

    private boolean sameName(String physicalName, String declaredName) {
        return physicalName != null
                && declaredName != null
                && physicalName.toLowerCase(Locale.ROOT).equals(declaredName.toLowerCase(Locale.ROOT));
    }
}
