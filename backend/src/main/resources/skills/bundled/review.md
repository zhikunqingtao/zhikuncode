# /review — 代码审查

## Goal
Review current uncommitted changes and provide actionable feedback.

## Steps
1. Run `git diff` to see all changes
2. For each changed file, analyze:
   - Correctness: logic errors, edge cases, null safety
   - Security: injection risks, credential exposure, unsafe operations
   - Performance: N+1 queries, unnecessary allocations, blocking calls
   - Style: naming conventions, code organization
3. Categorize findings by severity:
   - P0: Must fix (bugs, security issues)
   - P1: Should fix (performance, maintainability)
   - P2: Nice to fix (style, minor improvements)
4. Present findings with specific line references and suggested fixes

## Rules
- Be specific: cite exact lines and suggest concrete fixes
- Don't nitpick style if there's a linter configured
- Focus on things that matter: correctness > security > performance > style
