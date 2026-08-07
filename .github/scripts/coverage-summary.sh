#!/usr/bin/env bash

set -euo pipefail

readonly JAVA_CSV="${1:-bootui-coverage/target/site/jacoco-aggregate/jacoco.csv}"
readonly UI_JSON="${2:-bootui-ui/src/main/frontend/coverage/coverage-summary.json}"

printf '## Coverage\n\n'
printf 'Coverage is gated only for the critical scopes below; aggregate percentages are reported for trend visibility.\n\n'

if [[ -s "$JAVA_CSV" ]]; then
    awk -F, '
        NR > 1 {
            instruction_missed += $4
            instruction_covered += $5
            branch_missed += $6
            branch_covered += $7
            line_missed += $8
            line_covered += $9
        }
        END {
            instruction_total = instruction_missed + instruction_covered
            branch_total = branch_missed + branch_covered
            line_total = line_missed + line_covered
            printf "| Java aggregate | Instructions | Branches | Lines |\n"
            printf "| --- | ---: | ---: | ---: |\n"
            printf "| Current run | %.2f%% | %.2f%% | %.2f%% |\n\n",
                instruction_total ? 100 * instruction_covered / instruction_total : 0,
                branch_total ? 100 * branch_covered / branch_total : 0,
                line_total ? 100 * line_covered / line_total : 0
        }
    ' "$JAVA_CSV"
else
    printf '_Java aggregate report was not produced._\n\n'
fi

if [[ -s "$UI_JSON" ]]; then
    node - "$UI_JSON" <<'NODE'
const fs = require('node:fs')
const summary = JSON.parse(fs.readFileSync(process.argv[2], 'utf8')).total
console.log('| Frontend aggregate | Statements | Branches | Functions | Lines |')
console.log('| --- | ---: | ---: | ---: | ---: |')
console.log(
  `| Current run | ${summary.statements.pct}% | ${summary.branches.pct}% | ${summary.functions.pct}% | ${summary.lines.pct}% |\n`
)
NODE
else
    printf '_Frontend coverage report was not produced._\n\n'
fi

cat <<'EOF'
| Gated scope | Minimum |
| --- | ---: |
| Secret masking | 70% lines |
| Path normalization | 100% lines and branches |
| Shared Java safety policy | 90% lines, 75% branches |
| Spring/Quarkus exposure and MCP policy | 80% lines, 60% branches |
| Frontend path utilities | 80% statements, 75% branches, 95% functions, 85% lines |
| Shared frontend state primitives | 95% statements, 85% branches, 95% functions and lines |
| Shared accessible UI components | 90% statements and lines, 85% branches and functions |
EOF
