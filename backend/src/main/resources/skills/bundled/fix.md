# /fix — 智能修复

## Goal
Diagnose and fix errors in the current project based on error messages or failing tests.

## Steps
1. If an error message is provided, analyze it to identify the root cause
2. If no error is provided, run `git diff` to check recent changes that may have introduced issues
3. Search for related files using the error location (file, line number, stack trace)
4. Identify the root cause:
   - Syntax errors
   - Type mismatches
   - Missing imports or dependencies
   - Logic errors
   - Configuration issues
5. Apply the minimal fix that resolves the issue
6. Verify the fix by running relevant tests or build commands

## Rules
- Always identify root cause before applying fixes
- Prefer minimal, targeted fixes over large refactors
- If multiple issues are found, fix them one at a time
- Explain what caused the issue and why the fix works
- Run tests after fixing to verify no regressions
