# /commit — 智能提交

## Goal
Analyze staged changes and create a well-structured git commit.

## Steps
1. Run `git diff --cached --stat` to see what files changed
2. Run `git diff --cached` to see the actual changes
3. Analyze the changes and determine:
   - Type: feat/fix/refactor/docs/test/chore
   - Scope: affected module or component
   - Summary: concise description (max 72 chars)
4. Generate commit message in Conventional Commits format
5. Show the message to user for confirmation
6. Execute `git commit -m "<message>"`

## Rules
- First line must be <= 72 characters
- Use imperative mood ("Add feature" not "Added feature")
- If changes span multiple concerns, suggest splitting into multiple commits
- Include breaking change footer if applicable
