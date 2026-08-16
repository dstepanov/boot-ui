package io.github.jdubois.bootui.engine.hibernate;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * Framework-neutral bridge from the Hibernate metamodel ({@link HibernateEntityModel}) to the small, public,
 * JPA-annotation-free facts the Database Advisor cross-reference rules need.
 *
 * <p>Everything it reports is <em>explicit</em>: the declared {@code @Table} catalog/schema/name, the declared
 * {@code @JoinColumn}/{@code @JoinColumns} sets, the declared {@code @Column} name, and — as tri-state values
 * — the declared nullability and length. An entity relying on the default naming strategy is deliberately
 * reported without a table name, and an attribute with no explicit {@code length}/{@code nullable} is reported
 * as "not declared" rather than as the JPA default, because a rule that compares a default it invented against
 * the database produces findings the user cannot act on.</p>
 *
 * <p>Attributes whose persisted shape is decided by a converter, an {@code @Enumerated} mapping or an
 * {@code @Lob} are flagged, so the type/length rules can skip exactly the cases where the Java type says
 * nothing reliable about the physical column.</p>
 *
 * <p>This keeps every {@code jakarta.persistence} reflection detail confined to this package (only this class
 * is public outside {@code io.github.jdubois.bootui.engine.hibernate}), while letting the
 * {@code io.github.jdubois.bootui.engine.databaseadvisor} package stay free of Hibernate/JPA reflection
 * concerns and reuse the exact same metamodel the Hibernate Advisor already reads.</p>
 */
public final class HibernateSchemaBridge {

    private static final String TABLE_ANNOTATION = "jakarta.persistence.Table";

    private HibernateSchemaBridge() {}

    public static List<MappedEntityFacts> toMappedEntities(List<HibernateEntityModel> entities) {
        List<MappedEntityFacts> mapped = new ArrayList<>();
        for (HibernateEntityModel entity : entities) {
            mapped.add(toMappedEntity(entity));
        }
        return mapped;
    }

    private static MappedEntityFacts toMappedEntity(HibernateEntityModel entity) {
        Annotation table = tableAnnotation(entity.javaType());
        String tableName = annotationString(table, "name");
        String schema = annotationString(table, "schema");
        String catalog = annotationString(table, "catalog");
        List<MappedForeignKeyFacts> foreignKeys = new ArrayList<>();
        List<MappedColumnFacts> columns = new ArrayList<>();
        List<MappedUniqueConstraintFacts> uniqueConstraints =
                new ArrayList<>(tableUniqueConstraints(table, entity.name()));
        for (HibernateAttributeModel attribute : entity.attributes()) {
            if (attribute.isTransient()) {
                continue;
            }
            if (isOwningToOne(attribute)) {
                addForeignKey(attribute, foreignKeys);
                continue;
            }
            if (attribute.isAssociation()) {
                continue;
            }
            addColumn(attribute, columns, uniqueConstraints);
        }
        return new MappedEntityFacts(
                entity.name(), tableName, schema, catalog, foreignKeys, columns, uniqueConstraints);
    }

    private static void addColumn(
            HibernateAttributeModel attribute,
            List<MappedColumnFacts> columns,
            List<MappedUniqueConstraintFacts> uniqueConstraints) {
        Annotation column = attribute.columnAnnotation();
        String columnName = column == null ? null : attribute.annotationStringValue(column, "name");
        if (columnName == null || columnName.isBlank()) {
            // No explicit @Column(name): the physical name depends on the naming strategy, which this bridge
            // deliberately does not guess.
            return;
        }
        Boolean nullable = attribute.annotationBooleanValue(column, "nullable");
        Integer declaredLength = declaredLength(attribute, column);
        boolean ambiguousType = attribute.hasConvertAnnotation()
                || attribute.isEnumAttribute()
                || attribute.enumeratedAnnotation() != null
                || attribute.isLob();
        columns.add(new MappedColumnFacts(
                attribute.description(),
                columnName,
                nullable,
                attribute.rawType() == null ? null : attribute.rawType().getSimpleName(),
                declaredLength,
                attribute.isLob(),
                ambiguousType,
                attribute.hasId()));
        if (Boolean.TRUE.equals(attribute.annotationBooleanValue(column, "unique"))) {
            uniqueConstraints.add(new MappedUniqueConstraintFacts(attribute.description(), List.of(columnName)));
        }
    }

    /**
     * The declared {@code @Column(length=...)}, or {@code null} when the attribute simply did not declare one.
     *
     * <p>JPA defaults {@code length} to 255, so the annotation itself cannot tell "declared 255" from "not
     * declared". Anything equal to the default is therefore treated as not declared: reporting a
     * developer-invisible default against a deliberately narrower column is noise, not a finding.</p>
     */
    private static Integer declaredLength(HibernateAttributeModel attribute, Annotation column) {
        Integer length = attribute.annotationIntValue(column, "length");
        if (length == null || length == 255) {
            return null;
        }
        return length;
    }

    private static void addForeignKey(HibernateAttributeModel attribute, List<MappedForeignKeyFacts> foreignKeys) {
        List<String> columns = foreignKeyColumns(attribute);
        if (columns.isEmpty()) {
            return;
        }
        foreignKeys.add(new MappedForeignKeyFacts(attribute.description(), columns));
    }

    private static boolean isOwningToOne(HibernateAttributeModel attribute) {
        if (attribute.manyToOneAnnotation() != null) {
            return true;
        }
        Annotation oneToOne = attribute.oneToOneAnnotation();
        if (oneToOne == null) {
            return false;
        }
        String mappedBy = attribute.annotationStringValue(oneToOne, "mappedBy");
        return mappedBy == null || mappedBy.isBlank();
    }

    /**
     * The owning side's join columns in declaration order: a single {@code @JoinColumn}, or every
     * {@code @JoinColumn} of a composite {@code @JoinColumns}. A composite association where any member has no
     * explicit name resolves to nothing at all — a partially-known composite key cannot be matched against a
     * physical index without guessing the rest.
     */
    private static List<String> foreignKeyColumns(HibernateAttributeModel attribute) {
        Annotation joinColumns = attribute.joinColumnsAnnotation();
        if (joinColumns != null) {
            return compositeJoinColumns(attribute, joinColumns);
        }
        Annotation joinColumn = attribute.joinColumnAnnotation();
        if (joinColumn == null) {
            return List.of();
        }
        String name = attribute.annotationStringValue(joinColumn, "name");
        return name == null || name.isBlank() ? List.of() : List.of(name);
    }

    private static List<String> compositeJoinColumns(HibernateAttributeModel attribute, Annotation joinColumns) {
        Object value = annotationValue(joinColumns, "value");
        if (!(value instanceof Object[] members) || members.length == 0) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        for (Object member : members) {
            if (!(member instanceof Annotation joinColumn)) {
                return List.of();
            }
            String name = attribute.annotationStringValue(joinColumn, "name");
            if (name == null || name.isBlank()) {
                return List.of();
            }
            columns.add(name);
        }
        return List.copyOf(columns);
    }

    /**
     * Reads {@code @Table(uniqueConstraints = @UniqueConstraint(columnNames = {...}))} multi-column unique
     * constraints declared on the entity or one of its mapped superclasses.
     */
    private static List<MappedUniqueConstraintFacts> tableUniqueConstraints(Annotation table, String entityName) {
        if (table == null) {
            return List.of();
        }
        Object value = annotationValue(table, "uniqueConstraints");
        if (!(value instanceof Object[] uniqueConstraints)) {
            return List.of();
        }
        List<MappedUniqueConstraintFacts> facts = new ArrayList<>();
        for (Object uniqueConstraint : uniqueConstraints) {
            Object columnNames = annotationValue(uniqueConstraint, "columnNames");
            if (columnNames instanceof String[] names && names.length > 0) {
                facts.add(new MappedUniqueConstraintFacts(entityName + " @Table unique constraint", List.of(names)));
            }
        }
        return facts;
    }

    private static Annotation tableAnnotation(Class<?> javaType) {
        Class<?> current = javaType;
        while (current != null && current != Object.class) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                if (TABLE_ANNOTATION.equals(annotation.annotationType().getName())) {
                    return annotation;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static String annotationString(Annotation annotation, String attributeName) {
        Object value = annotationValue(annotation, attributeName);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private static Object annotationValue(Object annotation, String attributeName) {
        if (annotation == null) {
            return null;
        }
        try {
            Class<?> declaringType =
                    annotation instanceof Annotation typed ? typed.annotationType() : annotation.getClass();
            return declaringType.getMethod(attributeName).invoke(annotation);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * @param entityName the entity's fully-qualified Java type name
     * @param explicitTableName the explicit {@code @Table(name=...)} value, or {@code null} when the entity
     *     relies on the default naming strategy (deliberately not guessed)
     * @param explicitSchema the explicit {@code @Table(schema=...)} value, or {@code null}
     * @param explicitCatalog the explicit {@code @Table(catalog=...)} value, or {@code null}
     * @param foreignKeys owning {@code @ManyToOne}/{@code @OneToOne} associations with fully-resolved join
     *     column names, in declaration order
     * @param columns basic mapped attributes with an explicit {@code @Column(name=...)}
     * @param uniqueConstraints single-column {@code @Column(unique=true)} attributes and multi-column
     *     {@code @Table(uniqueConstraints=...)} constraints
     */
    public record MappedEntityFacts(
            String entityName,
            String explicitTableName,
            String explicitSchema,
            String explicitCatalog,
            List<MappedForeignKeyFacts> foreignKeys,
            List<MappedColumnFacts> columns,
            List<MappedUniqueConstraintFacts> uniqueConstraints) {

        public MappedEntityFacts {
            foreignKeys = List.copyOf(foreignKeys);
            columns = List.copyOf(columns);
            uniqueConstraints = List.copyOf(uniqueConstraints);
        }

        /** Convenience constructor for callers with no explicit schema/catalog and no unique constraints. */
        public MappedEntityFacts(
                String entityName,
                String explicitTableName,
                List<MappedForeignKeyFacts> foreignKeys,
                List<MappedColumnFacts> columns) {
            this(entityName, explicitTableName, null, null, foreignKeys, columns, List.of());
        }

        /** Convenience constructor for callers with no explicit schema/catalog. */
        public MappedEntityFacts(
                String entityName,
                String explicitTableName,
                List<MappedForeignKeyFacts> foreignKeys,
                List<MappedColumnFacts> columns,
                List<MappedUniqueConstraintFacts> uniqueConstraints) {
            this(entityName, explicitTableName, null, null, foreignKeys, columns, uniqueConstraints);
        }

        /** {@code schema.table} when a schema was declared, otherwise the bare declared table name. */
        public String qualifiedTableName() {
            if (explicitSchema == null || explicitSchema.isBlank()) {
                return explicitTableName;
            }
            return explicitSchema + "." + explicitTableName;
        }
    }

    /** @param attributeDescription a human-readable "Entity.attribute" description for finding details */
    public record MappedForeignKeyFacts(String attributeDescription, List<String> columns) {

        public MappedForeignKeyFacts {
            columns = List.copyOf(columns);
        }
    }

    /**
     * @param nullable the declared {@code @Column(nullable=...)}, or {@code null} when not declared
     * @param javaTypeSimpleName the attribute's raw Java type simple name (e.g. {@code String})
     * @param declaredLength the declared {@code @Column(length=...)}, or {@code null} when not declared or
     *     left at the JPA default
     * @param lob whether the attribute is an {@code @Lob}
     * @param ambiguousType whether a converter/{@code @Enumerated}/{@code @Lob} decides the persisted shape,
     *     so the Java type says nothing reliable about the physical column
     * @param identifier whether the attribute is the entity's {@code @Id}
     */
    public record MappedColumnFacts(
            String attributeDescription,
            String columnName,
            Boolean nullable,
            String javaTypeSimpleName,
            Integer declaredLength,
            boolean lob,
            boolean ambiguousType,
            boolean identifier) {

        /** Convenience constructor for callers with no length/LOB/converter information. */
        public MappedColumnFacts(
                String attributeDescription, String columnName, Boolean nullable, String javaTypeSimpleName) {
            this(attributeDescription, columnName, nullable, javaTypeSimpleName, null, false, false, false);
        }

        /** Convenience constructor for callers with an explicit declared length only. */
        public MappedColumnFacts(
                String attributeDescription,
                String columnName,
                Boolean nullable,
                String javaTypeSimpleName,
                Integer declaredLength) {
            this(attributeDescription, columnName, nullable, javaTypeSimpleName, declaredLength, false, false, false);
        }
    }

    /**
     * A mapped unique constraint, either a single-column {@code @Column(unique=true)} attribute or a
     * multi-column {@code @Table(uniqueConstraints=...)} constraint.
     *
     * @param description a human-readable description for finding details
     */
    public record MappedUniqueConstraintFacts(String description, List<String> columns) {

        public MappedUniqueConstraintFacts {
            columns = List.copyOf(columns);
        }
    }
}
