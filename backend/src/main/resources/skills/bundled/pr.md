# /pr — Pull Request 助手

## Goal
Prepare a well-structured pull request with description, changelog, and review notes.

## Steps
1. Run `git log --oneline main..HEAD` to list commits in this branch
2. Run `git diff main..HEAD --stat` to see all changed files
3. Run `git diff main..HEAD` to review the full diff
4. Generate PR description:
   - Title: concise summary of the change (Conventional Commits format)
   - Summary: what changed and why
   - Changes: bullet list of key modifications grouped by area
   - Testing: how the changes were tested
   - Breaking changes: any API or behavior changes
5. Identify potential review concerns:
   - Complex logic that needs careful review
   - Security-sensitive changes
   - Performance implications
6. Present the PR description for user review and editing

## Rules
- Keep the title under 72 characters
- Group related changes together in the description
- Highlight breaking changes prominently
- Include migration steps if applicable
- Reference related issues or tickets when mentioned in commits
- Flag files that need special attention from reviewers
