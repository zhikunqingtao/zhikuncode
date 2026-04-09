# /test — 智能测试

## Goal
Generate or run tests for the specified code or recent changes.

## Steps
1. Identify what needs testing:
   - If a file/function is specified, test that target
   - If no target, analyze `git diff` to find recently changed code
2. Determine the test framework in use (JUnit, Vitest, Jest, etc.)
3. For test generation:
   - Analyze the target code's public API
   - Generate test cases covering: happy path, edge cases, error cases
   - Follow existing test patterns in the project
4. For test execution:
   - Run the relevant test command
   - Report results: passed, failed, skipped
   - For failures, analyze and suggest fixes

## Rules
- Follow the project's existing test conventions and patterns
- Test public behavior, not implementation details
- Include edge cases: null/empty inputs, boundary values, error conditions
- Use descriptive test names that explain what is being tested
- Mock external dependencies appropriately
- Aim for meaningful coverage, not 100% line coverage
