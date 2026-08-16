# Database checks

The Database panel runs a fixed, on-demand ruleset against the physical schema of every discovered
application `DataSource` bean. It introspects tables, columns, primary keys, foreign keys, and indexes through plain
JDBC `DatabaseMetaData` — it never executes DDL and never queries application data.

The checks are deterministic, low-false-positive structural checks, not query/workload-based tuning suggestions. See
[FEATURES.md](FEATURES.md#database) for scope, availability, and dialect-detection details.

## Availability and bounds

The panel is available whenever at least one application `DataSource` bean is discovered (reusing the same
proxy-aware datasource discovery as Database Connection Pools and SQL Trace, so a wrapped/routing `DataSource` is
never introspected twice). If no `DataSource` bean is present, BootUI returns a stable empty report with a `DISABLED`
status rather than failing.

Every scan runs under fixed bounds so an on-demand scan can never turn into unbounded work: at most 300 tables, 300
columns and 100 indexes per table, and 500 rows per catalog augmentation query, within an overall 20-second budget,
with a 5-second query timeout on every catalog statement (clamped to the remaining budget). Row bounds are enforced by
reading one row past the limit, so reaching a bound is detected rather than silently returning a full-looking result.
The connection's original read-only state is restored before it goes back to the pool.

## What "could not be checked" looks like

Nothing that failed is ever reported as a passing check:

- **Datasources** each carry a read status — `AVAILABLE`, `PARTIAL` (a bound was reached or some metadata could not be
  read) or `FAILED` — plus the detected product/dialect. Credentials in a JDBC URL or driver error message are always
  redacted, regardless of the value-exposure policy.
- **Diagnostics** list every problem next to the findings: an unreachable datasource, a table whose metadata could not
  be read, a catalog view a database role cannot see, a bound that truncated the scan, and every rule that was skipped
  or errored. They are never counted as violations and never affect the advisor score.
- **Scan status** is `SCANNED` only when every datasource was fully read, nothing was truncated, and no rule errored;
  otherwise it is `PARTIAL`. A scan where *every* datasource failed reports `ERROR` (not `DISABLED`, which means "there
  is nothing to inspect").
- **Rules** report `SKIPPED` with the real reason (no datasource of that dialect, an unsupported server version, a
  catalog permission error) instead of a clean `PASS` they did not earn, and a table whose metadata could not be read is
  skipped by the rules that would otherwise conclude something is absent.

## Severity scale

- **HIGH** - a structural issue with a well-understood, common performance or data-integrity impact (a missing index
  supporting a foreign key, an invalid PostgreSQL index, an unvalidated constraint, a sequence or `AUTO_INCREMENT`
  counter nearing exhaustion, a foreign key/referenced column type mismatch, a mapped column or unique constraint the
  database does not actually have).
- **MEDIUM** - a structural issue that usually warrants review before production use (a missing primary key, a mapped
  table or column that disagrees with the physical schema, a non-transactional MySQL/MariaDB storage engine, a legacy
  `utf8mb3` character set).
- **LOW** - reserved for lower-impact hygiene findings (redundant indexes).

The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id. Each
rule includes up to 10 sample findings plus a remediation link.

The advisor score applies the shared severity penalty to every concrete finding, not just once per violated rule. For
example, eight HIGH missing-index findings incur eight HIGH penalties. Dismissing that rule removes all eight findings
from the score.

---

## Schema

Generic checks that run against every JDBC-reachable `DataSource`, regardless of database vendor, using only
standard `DatabaseMetaData` calls (enriched, where the vendor catalog can answer, with index semantics JDBC cannot
express).

### DB-SCHEMA-001 - Tables without a primary key

- **Severity**: MEDIUM
- **Inspects**: `DatabaseMetaData.getPrimaryKeys()` for every application table in the physical schema.
- **Fires when**: a table reports no primary key columns.
- **Excludes**: system and temporary schemas, extension-owned tables (PostgreSQL `pg_depend.deptype = 'e'`, e.g. PostGIS
  or `pg_stat_statements` bookkeeping), migration bookkeeping tables (`flyway_schema_history`, `schema_version`,
  `DATABASECHANGELOG`, `DATABASECHANGELOGLOCK`), PostgreSQL child partitions (analyzed through their parent), and any
  table whose primary key metadata could not be read.
- **Why it matters**: without a primary key, ORMs cannot establish row identity, logical replication tools cannot
  target individual rows, and `UPDATE`/`DELETE` statements risk affecting more rows than intended.
- **Recommendation**: declare a primary key (a natural key or a surrogate id) on every table.

### DB-SCHEMA-002 - Foreign key columns without a supporting index

- **Severity**: HIGH
- **Inspects**: `DatabaseMetaData.getImportedKeys()` foreign keys against the table's own indexes.
- **Fires when**: the constraint's **complete ordered column list** is not the leading prefix of any usable index. A
  composite foreign key `(tenant_id, order_id)` is not supported by an index leading on `tenant_id` alone.
- **Does not count as support**: an invalid index, an invisible/ignored index, a partial index (it only covers the rows
  matching its predicate), an expression index, or a MySQL/MariaDB prefix index (`name(10)` indexes ten characters, not
  the value).
- **Why it matters**: most databases do not automatically index foreign keys, so joins against the referenced table
  and cascading deletes/updates on the parent row can force a full table scan on the child table.
- **Recommendation**: create an index whose leading columns are exactly the foreign key's columns, in the same order.

### DB-SCHEMA-003 - Duplicate/redundant indexes

- **Severity**: LOW
- **Inspects**: every pair of indexes on the same table.
- **Fires when**: a **non-unique** index's ordered key parts are a leading prefix of another index's, and both share the
  same semantics: same columns, direction, collation, prefix lengths and expressions, same access method, same partial
  predicate, same visibility.
- **Excludes**: unique indexes as the redundant side (a unique index enforces a constraint the covering index does
  not), the primary key's own backing index, and partial/invalid indexes.
- **Why it matters**: every additional index slows down `INSERT`/`UPDATE`/`DELETE` and consumes storage; when one
  index's key parts are a leading prefix of another's with identical semantics, the shorter one is usually redundant.
- **Recommendation**: review both index definitions — including any index hints or constraints relying on them — before
  dropping the redundant one.

### DB-SCHEMA-004 - Foreign key column type mismatch with the referenced column

- **Severity**: HIGH
- **Inspects**: each foreign key column against the column it **actually references**
  (`getImportedKeys().PKCOLUMN_NAME`, which may be an alternate unique key rather than the primary key), comparing type
  family, integer width and signedness, numeric precision/scale, and declared length.
- **Fires when**: the child column's type family differs, or it is a narrower integer, a different signedness, a smaller
  numeric precision/scale, or a shorter declared length than the referenced column.
- **Why it matters**: an `INT` foreign key referencing a `BIGINT` primary key works until the parent's ids pass 2^31; a
  coarse type-family mismatch can silently truncate values, defeat query planner join optimizations, or fail outright.
- **Recommendation**: align the foreign key column's type with the referenced column's type (e.g. both `BIGINT`).
- **Note**: classification is driven by the JDBC `DATA_TYPE` code and whole-token type names, so a PostgreSQL
  `interval` column is not treated as an integer (it contains "int") and unclassifiable vendor types never produce a
  finding.

### DB-SCHEMA-005 - Redundant unique index duplicating the primary key

- **Severity**: LOW
- **Inspects**: every unique index on a table against that table's primary key columns.
- **Fires when**: a unique index covers exactly the primary key's columns **in the same order** and is not the primary
  key's own backing index (identified by the driver-reported `PK_NAME`, falling back to the unique index matching the
  primary key columns in order). Partial and expression indexes are excluded.
- **Why it matters**: every additional unique index slows down `INSERT`/`UPDATE`/`DELETE` and consumes storage; an
  extra unique index matching the primary key's columns duplicates a guarantee the primary key already enforces.
- **Recommendation**: check that no foreign key or application code references the index by name, then drop it.

## Dialect-specific (PostgreSQL, MySQL and MariaDB)

Dialect-specific catalog augmentation runs in addition to the generic checks above. The dialect is detected from
`DatabaseMetaData.getDatabaseProductName()`, the product version string and the JDBC URL — MariaDB is detected as its
own dialect even when reached through the MySQL driver, which reports the product name as "MySQL" and only reveals the
truth in the version string. Every other database (H2, SQL Server, Oracle, etc.) still runs the full generic ruleset
through the standard JDBC metadata fallback.

Catalog queries are version-aware: MySQL 8.0's `IS_VISIBLE` versus MariaDB 10.6's `IGNORED` index-visibility column,
MySQL 8.0.13's `EXPRESSION` functional key parts, and PostgreSQL 10's `pg_sequences` view and declarative partitioning
are each selected from the reported server version. When a server is too old, or a role cannot read a catalog view, the
matching rule reports `SKIPPED` with that reason.

### DB-PG-001 - Invalid PostgreSQL indexes

- **Severity**: HIGH
- **Inspects**: `pg_index.indisvalid`, `indisready` and `indislive` on PostgreSQL datasources only.
- **Fires when**: an index is reported unusable — typically left behind by a failed `CREATE INDEX CONCURRENTLY`.
- **Excludes**: partitioned index parents (`relkind = 'I'`, legitimately invalid until every child index is attached)
  and extension-owned indexes. Findings are schema-qualified and name the failing flags.
- **Why it matters**: an invalid index is never used by the query planner but still pays the full write cost of
  index maintenance.
- **Recommendation**: confirm no `CREATE INDEX CONCURRENTLY` is currently running, then drop and recreate the index
  (`DROP INDEX CONCURRENTLY` followed by `CREATE INDEX CONCURRENTLY`).

### DB-PG-002 - PostgreSQL sequence nearing exhaustion

- **Severity**: HIGH
- **Inspects**: `pg_sequences.last_value` against **`min(the sequence's max_value, the owning column's capacity)`**,
  resolving the owning `table.column` and its type through `pg_depend`. Requires PostgreSQL 10 or later.
- **Fires when**: a non-cycling sequence has consumed at least 80% of that effective ceiling.
- **Why it matters**: the classic failure is a `bigint` sequence — 0% of its own range forever — feeding an `integer`
  column that stops accepting inserts at 2,147,483,647. Measuring against the sequence's own maximum, as BootUI
  previously did, reports 0% right up to the outage.
- **Recommendation**: widen the owning column (and the sequence maximum), or restart the sequence after archiving old
  rows. Cycling sequences wrap instead of failing and are never reported.
- **Note**: percentages are computed in arbitrary precision, so a `bigint` range cannot overflow the arithmetic.

### DB-PG-003 - PostgreSQL NOT VALID constraint never validated

- **Severity**: HIGH
- **Inspects**: `pg_constraint.convalidated` for foreign key and check constraints on PostgreSQL datasources only,
  excluding system and extension-owned objects.
- **Fires when**: a constraint was added `NOT VALID` and never validated.
- **Why it matters**: `ADD CONSTRAINT ... NOT VALID` is the standard way to avoid a long lock on a large table, with
  the intent of running `VALIDATE CONSTRAINT` afterwards. When that never happens the constraint is enforced for new
  rows only: existing rows may already violate it, and the planner cannot rely on it. `getImportedKeys()` reports the
  foreign key as if it were fully enforced, so nothing else in the panel can see this.
- **Recommendation**: run `ALTER TABLE ... VALIDATE CONSTRAINT ...` (a `SHARE UPDATE EXCLUSIVE` lock) after fixing any
  offending rows.

### DB-MYSQL-001 - Tables on a non-transactional storage engine

- **Severity**: MEDIUM
- **Inspects**: `information_schema.tables.ENGINE` on MySQL **and MariaDB** datasources.
- **Fires when**: a table uses an engine whose defect is the absence of transactions: MyISAM, MERGE/MRG_MYISAM, MEMORY,
  CSV, ARCHIVE, BLACKHOLE, or MariaDB's Aria.
- **Excludes**: specialist engines a developer chooses deliberately (RocksDB/MyRocks, ColumnStore, NDB, FEDERATED,
  SPIDER, CONNECT, SEQUENCE, ...). "Not InnoDB" is not a finding when the engine was the point, which is why this is
  MEDIUM rather than HIGH.
- **Why it matters**: non-transactional engines do not enforce foreign keys, do not roll back, and use table-level
  locking, which surprises most JPA/Hibernate applications that assume ACID semantics.
- **Recommendation**: convert the table to InnoDB (`ALTER TABLE ... ENGINE=InnoDB`) during a maintenance window — the
  rewrite locks the table and changes its on-disk size.

### DB-MYSQL-002 - Tables/columns using the legacy utf8mb3 character set

- **Severity**: MEDIUM
- **Inspects**: `information_schema.tables.TABLE_COLLATION` (table defaults) and
  `information_schema.columns.CHARACTER_SET_NAME` (columns) on MySQL and MariaDB datasources.
- **Fires when**: a table default or column uses `utf8`/`utf8mb3`.
- **Excludes**: other legacy character sets (`latin1`, `ascii`, `binary`, ...), which are almost always a deliberate
  per-column choice; flagging each one made this rule pure noise on legacy schemas.
- **Why it matters**: MySQL's legacy `utf8` alias is a three-byte encoding that cannot store the full Unicode range
  (emoji, many CJK supplementary characters), so a developer who asked for Unicode did not get it — it surfaces as
  silent truncation or an insert failure.
- **Recommendation**: convert the column and the table default to `utf8mb4`. Re-check index key lengths first: utf8mb4
  needs 4 bytes per character, so an existing index on a long `VARCHAR` can exceed the maximum key length.

### DB-MYSQL-003 - MySQL/MariaDB AUTO_INCREMENT nearing exhaustion

- **Severity**: HIGH
- **Inspects**: `information_schema.tables.AUTO_INCREMENT` against the signed/unsigned capacity of the table's
  `AUTO_INCREMENT` column type (`information_schema.columns.COLUMN_TYPE`).
- **Fires when**: the counter has consumed at least 80% of that capacity.
- **Why it matters**: when the counter reaches the column's maximum, every subsequent insert fails with a duplicate-key
  error — the MySQL equivalent of PostgreSQL sequence exhaustion, and just as common a cause of a sudden outage.
- **Recommendation**: widen the `AUTO_INCREMENT` column (and every foreign key column referencing it) in the same
  migration.
- **Note**: capacity is signedness-aware (`int` stops at 2,147,483,647, `int unsigned` at 4,294,967,295) and computed in
  arbitrary precision, because `bigint unsigned` exceeds `Long.MAX_VALUE`. A table whose `AUTO_INCREMENT` the server
  does not report is skipped, never treated as zero.

## Hibernate mapping

These checks run only when a Hibernate `EntityManagerFactory`/metamodel is also available for the same application,
cross-referencing the physical schema against the mapped JPA entities the shared Hibernate metamodel reader already
reads.

Only entities with an explicit `@Table(name = ...)` are cross-referenced; entities relying on the default naming
strategy are skipped rather than guessed. Matching honors an explicitly declared `catalog`/`schema`, and a mapped name
that matches tables in **more than one** readable datasource is treated as ambiguous and skipped rather than attributed
to an arbitrary database. Rules that conclude something is *absent* additionally skip any table whose metadata was
truncated or partly unreadable.

### DB-HIB-001 - Mapped foreign key column has no physical index

- **Severity**: HIGH
- **Inspects**: `@ManyToOne`/`@OneToOne` `@JoinColumn` and composite `@JoinColumns` foreign keys against the physical
  schema's actual usable indexes.
- **Fires when**: the mapped join column(s) have no usable index leading on them, in declaration order — using the same
  usability semantics as `DB-SCHEMA-002`.
- **Why it matters**: unlike the Hibernate Advisor's own `HIB-MAP-019` (which only sees JPA-declared
  `@Table(indexes=...)` metadata), this rule sees the database's actual indexes — including ones created by a
  Flyway/Liquibase migration. Hibernate loads the association's target through those columns on every traversal.
- **Recommendation**: add a database index (via a migration) leading on the mapped foreign key column(s).

### DB-HIB-002 - Mapped entity table not found in the physical schema

- **Severity**: MEDIUM
- **Inspects**: entities with an explicit `@Table(name = ...)`, honoring the declared catalog/schema, against the tables
  of every readable datasource.
- **Fires when**: a mapped table is absent from all of them. Skipped entirely when any datasource's table list was
  truncated, since an unread table cannot be told from a missing one.
- **Why it matters**: this usually points to a stale entity, a missing migration, or the wrong
  datasource/persistence-unit wiring.
- **Recommendation**: verify the entity is mapped to the correct persistence unit/datasource, that a pending
  migration creates the table, or that the entity is stale and should be removed.

### DB-HIB-003 - Mapped column type/nullability mismatch

- **Severity**: MEDIUM
- **Inspects**: `@Column(name = ...)` attributes against the physical column's JDBC type family and nullability.
- **Fires when**: a coarse type-family mismatch is detected (e.g. a `String` attribute mapped to a numeric column), or
  an **explicitly declared** `@Column(nullable = ...)` disagrees with the database.
- **Excludes**: attributes whose persisted shape is decided by an `@Convert`, an `@Enumerated` or an `@Lob` (a converter
  legitimately stores a `String` in an `int` column), physical types that cannot be classified confidently, and
  nullability that the mapping never declared — JPA defaults `nullable` to `true`, so comparing an undeclared default
  against a `NOT NULL` column produced advice the developer could not act on.
- **Why it matters**: these mismatches usually surface at runtime as a surprising constraint violation or
  conversion failure rather than at compile time.
- **Recommendation**: align the entity mapping with the physical column definition.

### DB-HIB-004 - Mapped column length longer than the physical column size

- **Severity**: MEDIUM
- **Inspects**: **explicitly declared** `@Column(length = ...)` attributes against the physical string/char column's
  reported size.
- **Fires when**: the declared length exceeds what the column can hold.
- **Excludes**: attributes with no explicit length (JPA's invisible default of 255 is not a statement of intent),
  `@Lob` attributes, and columns with no bounded physical size — PostgreSQL reports `2147483647` for unbounded `text`,
  which is not a width.
- **Why it matters**: a mapping that permits more characters than the database column can hold either silently
  truncates input or fails with a data-truncation error, depending on the database's strictness.
- **Recommendation**: align the entity's `@Column(length = ...)` with the physical column size, or widen the
  physical column via a migration.

### DB-HIB-005 - Mapped unique constraint has no backing physical unique index

- **Severity**: HIGH
- **Inspects**: single-column `@Column(unique = true)` attributes and multi-column
  `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {...}))` constraints against the uniqueness the database
  actually enforces.
- **Fires when**: no unique index (or primary key) genuinely covers the mapped columns. Column order is ignored —
  uniqueness over `(a, b)` and `(b, a)` is the same guarantee — but a MySQL/MariaDB **prefix** unique index
  (`unique key (email(20))`), a partial index, an expression index, and an invalid or invisible index are **not**
  coverage.
- **Why it matters**: without enforced uniqueness the database never rejects the duplicate the mapping assumes cannot
  exist, so concurrent inserts create rows the application logic never expected.
- **Recommendation**: add a unique index or constraint (via a migration) covering the same column(s) in full.

### DB-HIB-006 - Mapped column not found in the physical table

- **Severity**: HIGH
- **Inspects**: explicitly named `@Column(name = ...)` attributes and `@JoinColumn(s)` join columns against
  `DatabaseMetaData.getColumns()` for the resolved physical table.
- **Fires when**: a mapped column does not exist physically.
- **Why it matters**: every query touching that attribute fails at runtime with "column does not exist", usually only
  on the code path that first selects it. It normally means a migration was never applied, was applied to a different
  schema, or the entity is ahead of the database. Hibernate's own `ddl-auto` validation covers the same ground at
  startup, but it is off in most applications and fails the boot instead of reporting.
- **Recommendation**: apply the missing migration, or correct the mapping.

### DB-HIB-007 - Mapped association has no physical foreign key constraint

- **Severity**: HIGH
- **Inspects**: mapped `@ManyToOne`/`@OneToOne` join column sets — including composite ones — against the foreign keys
  `DatabaseMetaData.getImportedKeys()` reports for the same table.
- **Fires when**: the entity model declares the association but the database enforces no matching constraint.
- **Why it matters**: without the constraint an orphaned child row is a normal insert as far as the database is
  concerned, so an association the entity model presents as guaranteed can resolve to a missing row at runtime, and
  cascading deletes are silently not enforced. It commonly happens when a schema was generated without constraints or a
  table was recreated without them.
- **Recommendation**: add the foreign key constraint via a migration.

## Deliberately not checked

The panel stays a structural, deterministic advisor rather than a tuning engine, so it proposes no
workload/unused/missing-index heuristics, no bloat/vacuum/analyze advice, no query-plan or `pg_stat_statements`
analysis, and never scans application data. It also takes no position on unmanaged tables, timestamp time-zone style,
PostgreSQL ownership/superuser configuration, or pool-versus-`max_connections` sizing, and adds no Oracle or SQL Server
vendor rules.
