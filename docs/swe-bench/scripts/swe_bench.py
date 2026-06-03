#!/usr/bin/env python3
"""
SWE-bench Python Harness for ZhikunCode
========================================
Drives the ZhikunCode REST API to solve SWE-bench instances and produces
predictions in the official all_preds.jsonl format.

Usage:
    python swe-bench/swe_bench.py \
        --dataset ./swe-bench-lite.json \
        --model qwen3.6-max-preview \
        --output ./swe-bench/results \
        --limit 5 --workers 1
"""

import argparse
import json
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import List, Optional

import requests

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_API_URL = "http://127.0.0.1:8080"
DEFAULT_MODEL = "qwen3.6-max-preview"
DEFAULT_TIMEOUT = 600
DEFAULT_MAX_TURNS = 50
DEFAULT_WORKERS = 1
HEALTH_CHECK_ENDPOINT = "/api/health/ready"
QUERY_ENDPOINT = "/api/query"
CONVERSATION_ENDPOINT = "/api/query/conversation"
ALLOWED_TOOLS = ["Read", "Edit", "Write", "Bash", "Grep", "Glob"]
MAX_RETRIES = 3
RETRY_DELAY = 5  # seconds

SWE_BENCH_SYSTEM_PROMPT = """\
You are an autonomous software engineer tasked with fixing a bug in a Python repository.

## SETUP — Read this FIRST

### Available Tools — Closed Set (6 tools, nothing else exists)

⚠️ ENVIRONMENT: You operate ALONE in an isolated sandbox with no delegation capability,
no internet access, and no tools beyond the 6 listed below. This is a hard system
limitation — any tool call not matching these exact names will fail silently and
consume your turn budget with zero benefit.

- **Read**: Read file contents. Read(path="<abs_path>", offset=<line>, limit=<n>)
  - offset: 0-based starting line (optional); limit: number of lines (optional)
  - Read(path="file.py") reads entire file; Read(path="file.py", offset=100, limit=50) reads lines 100-149
- **Edit**: Modify existing text in a file. Edit(path="<abs_path>", old_text="...", new_text="...")
- **Write**: Create or overwrite a file. Write(path="<abs_path>", content="...")
- **Bash**: Execute shell commands. Bash(command="<cmd>")
- **Grep**: Search file contents by regex. Grep(pattern="<regex>", path="<dir_or_file>")
- **Glob**: Find files by glob pattern. Glob(pattern="<glob>")

⚠️ CRITICAL: The following tools DO NOT EXIST in this environment:
- Agent, SubAgent, Delegate, Fork, Spawn, Search, Browse, WebSearch
If you attempt to call any non-existent tool, it will fail. Do NOT waste turns on unavailable tools.
You have ONLY these 6 tools: Read, Edit, Write, Bash, Grep, Glob. Use them directly.

WHY only these 6: This is a single-agent evaluation harness with no orchestration layer
and no tool registry. You must solve the problem entirely by yourself. If a tool call
returns an error, immediately switch to one of the 6 tools above — never retry a failed tool name.

### Working Directory
- Repository root: {repo_path}
- ALL file paths must be absolute, starting with {repo_path}
- Example: {repo_path}/django/core/validators.py
- NEVER use relative paths or paths starting with /backend/

### Task Boundary
- You are fixing a LOCAL codebase bug. No internet access is available.
- Do NOT attempt web searches, API calls, or external tool usage.
- Everything you need is in the repository at {repo_path}.

## MANDATORY Phase Transitions (You MUST follow this)

### Phase ANALYZE (Turns 1-3): Understand the problem
- Read the issue description carefully
- Run the failing test to see the error (use Bash)
- Identify the error type and likely location
- ✅ EXIT CONDITION: "I understand what's failing and approximately where"
- ⚠️ DO NOT spend more than 3 turns here. Move to LOCATE.

### Phase LOCATE (Turns 4-12): Find the exact code to fix
- Use Grep/Read to find the relevant source file
- Identify the specific function/method that needs changes
- ✅ EXIT CONDITION: "I found the exact file and function to modify"
- ⚠️ HARD LIMIT: By turn 12, you MUST have identified the fix location
- ⚠️ If unsure, make your BEST GUESS and proceed to FIX

### Phase FIX (Turns 13-40): Write the code fix
- Use Edit tool to modify the source code
- Make minimal, targeted changes
- ✅ EXIT CONDITION: Code changes are written to disk
- 🚨 CRITICAL RULE: You MUST call Edit or Write at least once during this phase
- 🚨 If you reach turn 15 without any Edit/Write call, STOP reading and START editing immediately

### Phase VERIFY (Turns 41-60): Run tests
- Use Bash to run the failing test again
- Confirm the test passes
- If it fails, iterate: read error → Edit fix → re-run test
- ✅ EXIT CONDITION: Target test passes

## ⚠️ ANTI-PATTERNS TO AVOID:
0. NEVER touch tests/, test_*.py, *_test.py, or any conftest.py — these are
   auto-stripped from the final patch; touching them is wasted work.
1. DO NOT keep reading files indefinitely without making changes
2. DO NOT end your turn if you haven't made any code edit yet (unless you're in ANALYZE/LOCATE phase)
3. DO NOT give up after a tool error - try alternative approaches
4. DO NOT use more than 12 turns for exploration without starting to write code
5. If you're unsure about the perfect fix, write your BEST ATTEMPT - an imperfect fix is better than no fix
6. If the same Bash/Grep returned identical output twice, switch strategy.
   Repeating the failed query is sunk cost.

## Tool Failure Recovery
- If a tool returns an error, do NOT give up. Try an alternative approach.
- Path not found? → Use Glob to discover the correct path
- Edit failed? → Try Write with the full file content instead
- Access denied? → Check if you're using the correct working directory path
- After 2 failed attempts on same approach → switch strategy entirely
- NEVER end your turn just because one tool call failed

## Workflow (MUST follow in order)

### Step 1: ANALYZE (Test-First)
- First, locate and read the FAIL_TO_PASS test(s):
  Grep(pattern="def test.*keyword", path="{repo_path}/tests/")
  Read(path="{repo_path}/tests/test_xxx.py")
- Understand WHAT the test expects (input → expected output)
- Then read the issue description to understand WHY it fails
- This gives you a concrete "target behavior" to code toward
- If no test keyword is obvious, search broadly:
  Bash(command="grep -r 'def test' {repo_path}/tests/ --include='*.py' | grep -i 'relevant_keyword' | head -10")

**Three-Anchor Extraction** (strongly recommended before moving to LOCATE):
1. Run the failing test to capture the full traceback:
   Bash(command="cd {repo_path} && python -m pytest tests/ -k '<keyword_from_issue>' -xvs 2>&1 | tail -80")
2. Extract THREE anchors from the output:
   a) ERROR_TYPE: The Python exception class (e.g., AttributeError, TypeError, ValueError)
   b) SOURCE_FILE: The first non-pytest .py file path from the traceback + line number
   c) FUNCTION_NAME: The function/method name where the exception was raised
3. Proceed to LOCATE after all three anchors are identified.
   If test cannot be found/run, derive best-guess anchors from the issue description.
⚠️ ANTI-PATTERN: Jumping directly to Grep/Glob without running the test first
   wastes turns and leads to wrong-file edits (the #1 cause of all-fail).

### Step 2: LOCATE (Selective Reading + Fallback Ladder)
- Use Grep/Glob to find relevant source files
- **FALLBACK LADDER** (if Grep/Glob return empty):
  Step A: Bash(command="grep -r 'search_term' {repo_path}/ --include='*.py' -l")  ← content search
  Step B: Bash(command="find {repo_path} -name '*.py' -path '*keyword*'")  ← file locate
  Step C: Bash(command="ls {repo_path}/<package_name>/")  ← directory explore
- **Selective Reading** (save tokens for large files):
  Read(path="file.py", offset=100, limit=50)  # Read 50 lines starting from line 100
  Read(path="file.py")  # OK for small files (<300 lines)
  For files >500 lines, NEVER read the entire file — always target specific sections
- Read the identified files to understand the current implementation
- Trace the code path that causes the bug
- Check existing tests to understand expected behavior
- **BEFORE writing new code, search for existing implementations**:
  Grep(pattern="def.*similar_keyword") to find reusable functions
  Grep(pattern="class.*Base.*") to find base class implementations
- Check if the fix could MODIFY 1 existing line instead of ADDING 10 new lines
- If the codebase already handles a similar case elsewhere, MIRROR that pattern
- **CRITICAL — Pattern Matching**: Before writing ANY fix, search for how the codebase
  handles analogous cases. Your fix MUST mirror existing patterns:
  Bash(command="grep -rn 'similar_keyword' {repo_path}/ --include='*.py' | head -10")
  If you find existing code that handles a similar case, replicate its approach exactly.

**Pattern Mirroring Workflow** (use when fix is non-obvious):
  a) Grep for the SYMPTOM (error type / function name) across the repo
  b) Find a similar resolved case (e.g. another except branch handling same exc)
  c) Mirror the exact structure: same indent, same idiom, same return type

### Step 2.8: PRE-EDIT BASELINE (recommended before any Edit on high-caller functions)

Before making any code change, establish a test baseline for the affected module:

1. Identify the most relevant test directory:
   Bash(command="find {repo_path} -path '*tests*' -name 'test_*.py' -newer {repo_path}/setup.py | head -5")
   OR use the same directory as the failing test.

2. Run a quick baseline (BEFORE any Edit):
   Bash(command="cd {repo_path} && python -m pytest <relevant_test_dir> -x --tb=no -q 2>&1 | tail -5")

3. Record the numbers: e.g., "42 passed, 3 failed, 1 error"

4. AFTER completing your fix, re-run the SAME command.

5. Compare:
   - If passed_after < passed_before: You introduced regression!
     Make your fix MORE LOCAL: prefer adding a branch over modifying existing logic.
     Use "if <new_condition>: ... else: <original_behavior>" pattern.
   - If passed_after >= passed_before: Safe to proceed.

This step costs 1 turn but saves 10+ turns of debugging regressions.

### ERROR_TYPE_SPECIFIC_STRATEGIES (select based on ERROR_TYPE from Step 1)

If ERROR_TYPE == "AttributeError":
  -> Priority: "def __init__" in SOURCE_FILE (instance attribute setup)
  -> Secondary: "@property" or "@<attr>.setter" definitions
  -> Tertiary: "self.<attr>" assignment sites across the class hierarchy
  -> Common fix: Add missing attribute in __init__, or fix property accessor

If ERROR_TYPE == "TypeError":
  -> Priority: Read function signature of the failing call (count parameters)
  -> Check: "def <func_name>(" — compare expected vs actual arg count
  -> Common fix: Add/remove parameter, add type coercion, fix default value

If ERROR_TYPE == "ValueError":
  -> Priority: Search for "raise ValueError" near SOURCE_FILE:LINE
  -> Check: Input validation logic at function entry point
  -> Common fix: Widen accepted range, add missing case to if/elif chain

If ERROR_TYPE == "AssertionError":
  -> Priority: Read the exact assertion in test to understand expected output
  -> Compare: What does current code produce vs what the test expects?
  -> Common fix: Fix return value, fix string format, fix comparison logic

If ERROR_TYPE == "ImportError" or "ModuleNotFoundError":
  -> Priority: Bash(command="python -c 'import <module>' 2>&1")
  -> Check: Missing install or wrong import path?
  -> Common fix: Fix import path, add __init__.py, or fix circular import

### Step 3: FIX (Minimal Change Priority)
- PRIORITY ORDER for fix approaches:
  1. Change a SINGLE condition/value (1 line) — BEST
  2. Add a SINGLE check/branch (2-5 lines) — GOOD
  3. Modify an existing function signature (5-10 lines) — OK
  4. Write a new helper function (10+ lines) — LAST RESORT
- If your fix exceeds 15 lines, STOP and reconsider:
  - Is there a simpler approach?
  - Are you reimplementing something that already exists?
  - Could you fix at a higher/lower level in the call chain?
- Do NOT refactor unrelated code
- Do NOT add new dependencies unless absolutely necessary
- Prefer fixing at the source rather than adding workarounds
- **ANTI-PATTERN CHECK**: If your fix introduces a NEW function, class, or import, STOP.
  Search if an equivalent already exists in the codebase:
  Grep(pattern="def.*function_name", path="{repo_path}/")
  Bash(command="grep -rn 'class.*ClassName' {repo_path}/ --include='*.py'")
  The codebase likely already has the mechanism you need — reuse it instead of reimplementing.

### Step 3.5: VALIDATE FIX (MANDATORY before proceeding)

Before running tests, verify your fix by answering these 6 questions:

1. **Variable Lifecycle**: If your fix introduces or moves variable assignments,
   does EVERY code path that references that variable have a valid assignment?

2. **Type Semantics**: If your fix involves comparison operators,
   confirm the operand types are compatible.

3. **Related Constraints**: Search the codebase for existing assertions related
   to the same concept. Your new code must not conflict.

4. **Call Path Verification**: Read the FAIL_TO_PASS test to identify the exact
   call chain. Confirm your fix is on that call path.

5. **Completeness Check**: If the fix requires changes in >1 location,
   list ALL required changes before editing.

6. **Minimality Check**: Compare your fix size with the problem complexity:
   - Single-line regex/condition bug → fix should be 1-3 lines
   - Missing error handling → fix should be 3-10 lines
   - Logic restructure → fix should be 10-20 lines MAX
   - If your fix exceeds these bounds, search for a simpler alternative

7. **Equivalence Check**: Read the failing test's assertion carefully.
   Does your implementation produce EXACTLY the same output format, type, and value
   that the test expects? If the test expects `str`, don't return `bytes`.
   If it expects a list, don't return a generator. If it checks `.message`, ensure
   your exception has that attribute set correctly.

If ANY answer reveals a problem, revise your fix BEFORE proceeding to Step 4.

### Step 4: VERIFY (MANDATORY — NEVER skip — costs 0 extra effort)

⚠️ YOU MUST RUN TESTS. A fix without test verification is INCOMPLETE.

0. **Confirm test environment is usable** (do this ONCE at start):
   Bash(command="cd {repo_path} && python -m pytest --collect-only 2>&1 | tail -5")
   - If collection succeeds → proceed to step 1
   - If ImportError/ModuleNotFound → Bash(command="cd {repo_path} && pip install -e . 2>&1 | tail -20") then retry
   - If fixture errors → find the correct conftest: Bash(command="find {repo_path} -name 'conftest.py' -type f")

1. **Find the test**:
   - Check problem statement for test file/method names
   - If not explicit: Bash(command="find {repo_path} -path '*/tests/*' -name '*.py' | xargs grep -l 'relevant_keyword' | head -5")

2. **Run FAIL_TO_PASS test**:
   Bash(command="cd {repo_path} && python -m pytest tests/path/test_file.py::test_method -xvs 2>&1 | (head -20; echo '...'; tail -120)")
   Note: head -20 captures setup/collection info; tail -120 captures full error traceback

3. **Failure Classification Decision Tree**:
   - **AssertionError / Wrong output** → LOGIC problem → go back to Step 3, revise the fix
   - **ImportError / ModuleNotFoundError** → ENVIRONMENT problem → Bash(command="cd {repo_path} && pip install -e . 2>&1 | tail -20") then re-run
   - **fixture 'xxx' not found** → WRONG TEST FILE → find correct test: Bash(command="grep -r 'def test_' {repo_path}/tests/ | grep -i 'keyword'")
   - **TIMEOUT / hung** → DO NOT retry → submit current patch as-is (likely infinite loop in fix)
   - **PASSED** → Done. Stop immediately.

4. **Iteration Rules**:
   - Max 5 fix-verify cycles for LOGIC errors
   - Max 1 retry for ENVIRONMENT errors (if pip install doesn't fix it, move on)
   - ZERO retries for TIMEOUT (submit immediately)

5. **Run PASS_TO_PASS tests** (prevent regression, only after FAIL_TO_PASS passes):
   Bash(command="cd {repo_path} && python -m pytest tests/path/ -x --timeout=60 2>&1 | tail -30")

ITERATION BUDGET: Max 5 fix-verify cycles total. If fix #5 still fails, submit your best attempt.

## Critical Rules
1. MINIMAL CHANGES ONLY — The fewer lines changed, the better
2. NO unnecessary imports, comments, or whitespace changes
3. If unsure about a fix, read more code first
4. Always verify your fix compiles/runs before finishing
5. When done, simply stop — do not announce completion
6. NEVER modify test files or conftest.py
7. NEVER add print/debug statements in final code
8. Prefer Edit over Write for targeted changes
9. Before editing, always run: Grep for assert/raise/if related to the same concept
10. After editing, mentally trace the FAIL_TO_PASS test's exact call path through your fix

## TROUBLESHOOTING — Fallback Ladder (High-precision tool → General Bash)

### Grep returns empty?
Grep is a content search tool. When it fails, use the Bash equivalent:
1. Bash(command="grep -r 'keyword' {repo_path}/ --include='*.py' -l")  ← content search
2. Bash(command="grep -rn 'keyword' {repo_path}/ --include='*.py' | head -30")  ← with line numbers
3. If still empty, broaden the pattern or try synonyms

### Read fails (file not found / permission denied)?
Read is a file content tool. Fallback ladder:
1. Bash(command="ls -la <path>")  ← confirm path exists
2. If file EXISTS: Bash(command="head -100 <path>") or Bash(command="cat <path>")  ← read via Bash
3. If file NOT FOUND: Bash(command="find {repo_path} -name '<filename>'")  ← relocate
4. Once correct path found, retry Read with the new absolute path

### Glob returns empty?
Glob is a file-finding tool. Fallback:
1. Bash(command="find {repo_path} -type f -name 'pattern'")  ← file find
2. Bash(command="find {repo_path} -path '*keyword*' -name '*.py'")  ← fuzzy match

### Edit fails?
1. Read the file first to confirm exact current text (copy-paste match)
2. If file too large, use: Bash(command="sed -n '100,120p' <path>")  ← view exact lines
3. Retry Edit with corrected old_text

### Path not found?
1. ALWAYS use absolute paths starting with {repo_path}
2. Bash(command="find {repo_path} -name 'filename.py' -type f")
3. Bash(command="ls {repo_path}/<likely_subdir>/")

### Tool does not exist?
- ONLY use: Read, Edit, Write, Bash, Grep, Glob
- If you tried another tool, IMMEDIATELY switch to one of the 6 above
- Bash is the universal fallback (cat, find, sed, grep, head, tail, etc.)

### Stuck in a loop?
- If the same approach failed twice, try a DIFFERENT strategy
- Re-read the problem statement for missed clues
- Read the test file to understand expected behavior
- Use Bash(command="find {repo_path}/tests -name '*.py' | head -10") to discover test structure

## COMPLETION GATE — Minimum Viable Session

Your session is NOT complete until ALL of the following are true:
1. You have READ at least one source file (not just searched for filenames)
2. You have made at least one code change via Edit or Write to a source file
3. You have attempted to VERIFY the change (run the relevant test or at minimum confirm no syntax error)

A session that ends with only search operations (Grep/Glob/Bash "find") and no Edit/Write
is a FAILED session — equivalent to producing no output at all.

If you feel stuck after searching:
- Ask: "Have I READ the file content, or only found its path?" → If only path, Read it now.
- Ask: "Do I understand the root cause?" → If no, read the failing test and trace the call chain.
- Ask: "Can I write ANY fix, even imperfect?" → A reasonable attempt beats no attempt.
  Write your best fix now. An imperfect patch that addresses the right location is
  more valuable than an empty submission.

## 🚨 FINAL WARNING — DO NOT EXPLORE ENDLESSLY
Your #1 failure mode is spending all turns reading/searching and NEVER writing a fix.
A session that ends with 0 Edit/Write calls is a COMPLETE FAILURE worth 0 points.
Even a wrong fix attempt has a chance of passing. No fix attempt = guaranteed failure.
After turn 12: If you haven't called Edit or Write yet, do it NOW on your best guess.
"""

# ---------------------------------------------------------------------------
# Multi-phase prompts (used by solve_instance_multiphase)
# ---------------------------------------------------------------------------

VERIFY_PHASE_PROMPT = """\
## VERIFY YOUR FIX

You MUST run tests to verify your changes. A text-only response without Bash execution is a FAILED session.

1. Find the relevant test file:
   Bash(command="grep -r 'def test' {repo_path}/tests/ --include='*.py' | grep -i '<keyword_from_problem>' | head -5")

2. Run the test:
   Bash(command="cd {repo_path} && python -m pytest <test_file> -xvs 2>&1 | (head -20; echo '...'; tail -80)")

3. If PASSED -> stop immediately. Your fix is correct.

4. If FAILED -> analyze the error, refine your fix with Edit, then re-run the test.
   Max 3 fix-verify cycles.

5. After FAIL_TO_PASS test passes, run broader tests to check for regressions:
   Bash(command="cd {repo_path} && python -m pytest <test_dir> -x --timeout=60 2>&1 | tail -30")

MANDATORY: Execute at least one Bash command to run tests.
Budget: up to 20 tool calls."""

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("swe_bench")


# ---------------------------------------------------------------------------
# Diagnostic utilities
# ---------------------------------------------------------------------------


def get_turn_progress(current_turn: int, max_turns: int) -> str:
    """Generate a turn progress string with phase information."""
    turn_progress = f"\n[Turn {current_turn}/{max_turns}]"
    if current_turn <= 3:
        turn_progress += " [Phase: ANALYZE - understand the problem]"
    elif current_turn <= 12:
        turn_progress += " [Phase: LOCATE - find the code to fix]"
    elif current_turn <= 40:
        turn_progress += " [Phase: FIX - write code changes NOW]"
        if current_turn >= 15:
            turn_progress += " \u26a0\ufe0f You MUST have made Edit/Write calls by now!"
    else:
        turn_progress += " [Phase: VERIFY - run tests]"
    return turn_progress


def analyze_tool_calls(response: Optional[dict]) -> dict:
    """Analyze tool calls from a response and return statistics."""
    stats = {
        "total_calls": 0,
        "tools_used": [],
        "tool_counts": {"Read": 0, "Edit": 0, "Write": 0, "Bash": 0, "Grep": 0, "Glob": 0, "Unknown": 0},
        "has_edit_or_write": False,
        "error_count": 0,
        "stop_reason": "unknown",
    }
    if response is None:
        return stats

    stats["stop_reason"] = response.get("stopReason", "unknown")
    tool_calls = response.get("toolCalls", [])
    stats["total_calls"] = len(tool_calls)

    for tc in tool_calls:
        tool_name = tc.get("tool", "unknown")
        stats["tools_used"].append(tool_name)
        if tool_name in stats["tool_counts"]:
            stats["tool_counts"][tool_name] += 1
        else:
            stats["tool_counts"]["Unknown"] += 1
        if tool_name in ("Edit", "Write"):
            stats["has_edit_or_write"] = True
        if tc.get("isError", False):
            stats["error_count"] += 1

    return stats


def log_phase_diagnostics(instance_id: str, phase_name: str, response: Optional[dict], turn_offset: int = 0):
    """Log diagnostic information for a phase."""
    tool_stats = analyze_tool_calls(response)
    turn_count = tool_stats["total_calls"]

    logger.info(f"  [{instance_id}] {phase_name} diagnostics:")
    logger.info(f"    Turns used: {turn_count} (offset: {turn_offset})")
    logger.info(f"    Stop reason: {tool_stats['stop_reason']}")
    logger.info(f"    Tool distribution: Read={tool_stats['tool_counts']['Read']}, "
                f"Edit={tool_stats['tool_counts']['Edit']}, Write={tool_stats['tool_counts']['Write']}, "
                f"Bash={tool_stats['tool_counts']['Bash']}, Grep={tool_stats['tool_counts']['Grep']}, "
                f"Glob={tool_stats['tool_counts']['Glob']}, Unknown={tool_stats['tool_counts']['Unknown']}")
    logger.info(f"    Errors: {tool_stats['error_count']}")

    if not tool_stats["has_edit_or_write"]:
        logger.warning(f"  [{instance_id}] ⚠️ WARNING: {phase_name} completed with NO Edit/Write calls!")

    return tool_stats


def log_diagnostics(instance_id: str, response: Optional[dict]):
    """Log diagnostic information for a single-session run."""
    tool_stats = analyze_tool_calls(response)
    turn_count = tool_stats["total_calls"]

    logger.info(f"  [{instance_id}] Session diagnostics:")
    logger.info(f"    Total tool calls: {turn_count}")
    logger.info(f"    Stop reason: {tool_stats['stop_reason']}")
    logger.info(f"    Tool distribution: Read={tool_stats['tool_counts']['Read']}, "
                f"Edit={tool_stats['tool_counts']['Edit']}, Write={tool_stats['tool_counts']['Write']}, "
                f"Bash={tool_stats['tool_counts']['Bash']}, Grep={tool_stats['tool_counts']['Grep']}, "
                f"Glob={tool_stats['tool_counts']['Glob']}, Unknown={tool_stats['tool_counts']['Unknown']}")
    logger.info(f"    Errors: {tool_stats['error_count']}")

    if not tool_stats["has_edit_or_write"]:
        logger.warning(f"  [{instance_id}] ⚠️ WARNING: Session completed with NO Edit/Write calls!")

    return tool_stats


def log_instance_summary(instance_id: str, all_phase_stats: list):
    """Log final summary for an instance with aggregated tool statistics."""
    total_turns = sum(s["total_calls"] for s in all_phase_stats)
    total_tools = {"Read": 0, "Edit": 0, "Write": 0, "Bash": 0, "Grep": 0, "Glob": 0, "Unknown": 0}
    total_errors = 0
    has_any_edit = False

    for s in all_phase_stats:
        for tool, count in s["tool_counts"].items():
            total_tools[tool] += count
        total_errors += s["error_count"]
        if s["has_edit_or_write"]:
            has_any_edit = True

    logger.info(f"  [{instance_id}] === INSTANCE SUMMARY ===")
    logger.info(f"    Total turns: {total_turns}")
    logger.info(f"    Aggregated tools: Read={total_tools['Read']}, Edit={total_tools['Edit']}, "
                f"Write={total_tools['Write']}, Bash={total_tools['Bash']}, "
                f"Grep={total_tools['Grep']}, Glob={total_tools['Glob']}")
    logger.info(f"    Total errors: {total_errors}")

    if not has_any_edit:
        logger.warning(f"  [{instance_id}] \u26a0\ufe0f CRITICAL WARNING: Instance ended with ZERO Edit/Write calls! "
                       f"This means 0 lines patch (guaranteed failure).")

# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass
class Instance:
    instance_id: str
    repo: str
    base_commit: str
    problem_statement: str


@dataclass
class Prediction:
    instance_id: str
    model_name_or_path: str
    model_patch: str


@dataclass
class RunStats:
    total: int = 0
    completed: int = 0
    skipped: int = 0
    failed: int = 0
    empty_patch: int = 0
    errors: List[str] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Dataset loading
# ---------------------------------------------------------------------------


def load_dataset(dataset_path: str, limit: int = 0) -> List[Instance]:
    """Load SWE-bench instances from a JSONL file."""
    path = Path(dataset_path)
    if not path.exists():
        logger.error(f"Dataset file not found: {dataset_path}")
        sys.exit(1)

    instances = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            data = json.loads(line)
            instances.append(Instance(
                instance_id=data["instance_id"],
                repo=data["repo"],
                base_commit=data["base_commit"],
                problem_statement=data["problem_statement"],
            ))

    if limit > 0:
        instances = instances[:limit]

    logger.info(f"Loaded {len(instances)} instances from {dataset_path}")
    return instances


# ---------------------------------------------------------------------------
# Repository management
# ---------------------------------------------------------------------------


def clone_and_checkout(repo: str, base_commit: str, work_dir: str) -> Optional[str]:
    """Shallow-fetch a single commit from GitHub. Fast even for huge repos."""
    repo_url = f"https://github.com/{repo}.git"  # HTTPS — no SSH key needed
    repo_name = repo.replace("/", "__")
    repo_path = os.path.join(work_dir, repo_name)

    try:
        # 1) git init
        os.makedirs(repo_path, exist_ok=True)
        subprocess.run(["git", "init", "-q"], cwd=repo_path,
                        check=True, capture_output=True, text=True, timeout=30)

        # 2) git remote add
        subprocess.run(["git", "remote", "add", "origin", repo_url], cwd=repo_path,
                        check=True, capture_output=True, text=True, timeout=10)

        # 3) shallow fetch the exact commit
        logger.info(f"Shallow-fetching {repo} @ {base_commit[:8]} ...")
        subprocess.run(
            ["git", "fetch", "--quiet", "--depth=1", "origin", base_commit],
            cwd=repo_path, check=True, capture_output=True, text=True, timeout=300,
        )

        # 4) checkout FETCH_HEAD
        logger.info(f"Checking out {base_commit[:8]} ...")
        subprocess.run(
            ["git", "checkout", "-q", "FETCH_HEAD"],
            cwd=repo_path, check=True, capture_output=True, text=True, timeout=120,
        )

        return repo_path
    except subprocess.TimeoutExpired:
        logger.error(f"Clone/checkout timed out for {repo}")
        return None
    except subprocess.CalledProcessError as e:
        logger.error(f"Clone/checkout failed for {repo}: {e.stderr}")
        return None


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------


def wait_for_backend(api_url: str, timeout: int = 60) -> bool:
    """Wait for the backend to be ready."""
    url = f"{api_url}{HEALTH_CHECK_ENDPOINT}"
    start = time.time()
    while time.time() - start < timeout:
        try:
            resp = requests.get(url, timeout=5, proxies={"http": None, "https": None})
            if resp.status_code == 200 and "READY" in resp.text.upper():
                logger.info("Backend is ready.")
                return True
        except requests.ConnectionError:
            pass
        time.sleep(2)
    logger.error(f"Backend not ready after {timeout}s at {api_url}")
    return False


# ---------------------------------------------------------------------------
# REST API interaction
# ---------------------------------------------------------------------------


def call_agent(
    api_url: str,
    problem_statement: str,
    working_directory: str,
    model: str,
    max_turns: int,
    timeout: int,
    system_prompt: str = None,
    session_id: str = None,
    is_continuation: bool = False,
) -> Optional[dict]:
    """Call the ZhikunCode REST API with retries.

    If is_continuation=True and session_id is provided, uses the
    /api/query/conversation endpoint to continue an existing session.
    Otherwise uses /api/query for a fresh call.
    """
    if is_continuation and session_id:
        # Continuation call: use /api/query/conversation
        url = f"{api_url}{CONVERSATION_ENDPOINT}"
        payload = {
            "sessionId": session_id,
            "prompt": problem_statement,
            "model": model,
            "systemPrompt": system_prompt or SWE_BENCH_SYSTEM_PROMPT,
            "allowedTools": ALLOWED_TOOLS,
            "permissionMode": "SKIP_ALL_PROMPTS",
            "maxTurns": max_turns,
            "workingDirectory": working_directory,
            "timeoutSeconds": timeout,
        }
    else:
        # First call: use /api/query
        url = f"{api_url}{QUERY_ENDPOINT}"
        payload = {
            "prompt": problem_statement,
            "model": model,
            "systemPrompt": system_prompt or SWE_BENCH_SYSTEM_PROMPT,
            "workingDirectory": working_directory,
            "allowedTools": ALLOWED_TOOLS,
            "permissionMode": "SKIP_ALL_PROMPTS",
            "maxTurns": max_turns,
            "timeoutSeconds": timeout,
        }
        if session_id:
            payload["sessionId"] = session_id

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            logger.info(f"  API call attempt {attempt}/{MAX_RETRIES} ...")
            resp = requests.post(
                url,
                json=payload,
                timeout=timeout + 120,  # HTTP timeout generously longer than agent timeout
                proxies={"http": None, "https": None},
            )
            if resp.status_code == 200:
                return resp.json()
            else:
                logger.warning(
                    f"  API returned {resp.status_code}: {resp.text[:200]}"
                )
        except requests.Timeout:
            logger.warning(f"  API call timed out (attempt {attempt})")
        except requests.ConnectionError as e:
            logger.warning(f"  Connection error (attempt {attempt}): {e}")

        if attempt < MAX_RETRIES:
            time.sleep(RETRY_DELAY * attempt)

    logger.error("  All API retry attempts exhausted.")
    return None


# ---------------------------------------------------------------------------
# Patch extraction
# ---------------------------------------------------------------------------


def extract_patch(repo_path: str, base_commit: str) -> str:
    """Extract git diff of all changes in the working tree (staged + unstaged).
    For shallow clones we compare against HEAD (which IS the base commit).
    Filters out .backup files (created by Edit tool) and test files."""
    try:
        # Stage everything so we capture new files too
        subprocess.run(
            ["git", "add", "-A"],
            cwd=repo_path, capture_output=True, text=True, timeout=30,
        )

        # Unstage .backup files and other noise artifacts
        # The Edit tool creates .backup/ directories as safety copies
        subprocess.run(
            ["git", "reset", "HEAD", "--", "*/.backup", "*/.backup/*",
             "*.backup.*", ".backup"],
            cwd=repo_path, capture_output=True, text=True, timeout=30,
        )

        # Only diff Python source files, exclude test files
        # This ensures clean patches suitable for SWE-bench submission
        result = subprocess.run(
            ["git", "diff", f"{base_commit}..HEAD", "--",
             "*.py",
             ":!tests/", ":!**/tests/",
             ":!test_*", ":!**/test_*",
             ":!*_test.py", ":!**/*_test.py",
             ":!conftest.py", ":!**/conftest.py"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode != 0:
            logger.warning(f"  git diff failed: {result.stderr}")
            return ""

        patch = result.stdout

        # Remove binary diffs (safety net)
        cleaned_lines = []
        skip_binary = False
        for line in patch.split("\n"):
            if line.startswith("Binary files"):
                skip_binary = True
                continue
            if line.startswith("diff --git") and skip_binary:
                skip_binary = False
            if not skip_binary:
                cleaned_lines.append(line)

        # Additional filter: remove any remaining .backup references
        # (belt-and-suspenders approach)
        final_lines = []
        skip_block = False
        for line in cleaned_lines:
            if line.startswith("diff --git") and ".backup" in line:
                skip_block = True
                continue
            if line.startswith("diff --git") and ".backup" not in line:
                skip_block = False
            if not skip_block:
                final_lines.append(line)

        patch = "\n".join(final_lines)

        # Audit: compare raw (unfiltered) diff with filtered result
        raw_result = subprocess.run(
            ["git", "diff", f"{base_commit}..HEAD", "--", "*.py"],
            cwd=repo_path, capture_output=True, text=True, timeout=30
        )
        raw = raw_result.stdout
        if raw and not patch:
            logger.warning(f"  [audit] All {len(raw.splitlines())} diff lines were"
                           f" stripped by exclude rules — agent may have edited only test files.")
        elif raw and patch and abs(len(raw) - len(patch)) > 100:
            logger.info(f"  [audit] Stripped {len(raw)-len(patch)} chars of test-related diff")

        return patch
    except subprocess.TimeoutExpired:
        logger.warning("  git diff timed out")
        return ""
    except Exception as e:
        logger.warning(f"  Patch extraction error: {e}")
        return ""


# ---------------------------------------------------------------------------
# Trajectory saving
# ---------------------------------------------------------------------------


def save_trajectory(trajs_dir: Path, instance_id: str, response: Optional[dict]):
    """Save the agent's reasoning trajectory as a .md file."""
    traj_path = trajs_dir / f"{instance_id}.md"
    lines = [f"# {instance_id}\n"]

    if response is None:
        lines.append("## Error\n\nAPI call failed — no response received.\n")
    else:
        # Result summary
        lines.append(f"## Result\n\n{response.get('result', 'N/A')}\n")

        # Stop reason
        stop_reason = response.get("stopReason", "unknown")
        lines.append(f"## Stop Reason: `{stop_reason}`\n")

        # Usage
        usage = response.get("usage", {})
        if usage:
            lines.append("## Token Usage\n")
            lines.append(f"- Input: {usage.get('inputTokens', 0)}")
            lines.append(f"- Output: {usage.get('outputTokens', 0)}")
            lines.append(f"- Cache Read: {usage.get('cacheReadInputTokens', 0)}")
            lines.append(f"- Cache Creation: {usage.get('cacheCreationInputTokens', 0)}\n")

        # Tool calls
        tool_calls = response.get("toolCalls", [])
        if tool_calls:
            lines.append(f"## Tool Calls ({len(tool_calls)} total)\n")
            for i, tc in enumerate(tool_calls, 1):
                tool_name = tc.get("tool", "unknown")
                is_error = tc.get("isError", False)
                status = " [ERROR]" if is_error else ""
                lines.append(f"### {i}. {tool_name}{status}\n")
                # Input
                inp = tc.get("input", {})
                if isinstance(inp, dict):
                    inp_str = json.dumps(inp, indent=2, ensure_ascii=False)
                else:
                    inp_str = str(inp)
                lines.append(f"**Input:**\n```json\n{inp_str}\n```\n")
                # Output (truncated)
                out = tc.get("output", "")
                if len(str(out)) > 2000:
                    out = str(out)[:2000] + "\n... [truncated]"
                lines.append(f"**Output:**\n```\n{out}\n```\n")

        # Error
        if response.get("error"):
            lines.append(f"## Error\n\n```\n{response['error']}\n```\n")

    traj_path.write_text("\n".join(lines), encoding="utf-8")


# ---------------------------------------------------------------------------
# Resumption support
# ---------------------------------------------------------------------------


def load_completed_ids(output_dir: Path) -> set:
    """Load instance IDs that have already been predicted."""
    preds_file = output_dir / "all_preds.jsonl"
    completed = set()
    if preds_file.exists():
        with open(preds_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    data = json.loads(line)
                    completed.add(data["instance_id"])
                except (json.JSONDecodeError, KeyError):
                    pass
    if completed:
        logger.info(f"Found {len(completed)} already-completed predictions. Will skip.")
    return completed


# ---------------------------------------------------------------------------
# Core solve loop
# ---------------------------------------------------------------------------


def solve_instance(
    instance: Instance,
    api_url: str,
    model: str,
    max_turns: int,
    timeout: int,
    output_dir: Path,
) -> Optional[Prediction]:
    """Solve a single SWE-bench instance end-to-end."""
    logger.info(f"━━━ [{instance.instance_id}] Starting ━━━")
    logger.info(f"  Repo: {instance.repo} @ {instance.base_commit[:8]}")

    # Create isolated temp directory for this instance
    # Use a dir under the output directory (avoids sandbox write restrictions on /var/folders)
    instances_dir = str(output_dir / "_workdirs")
    os.makedirs(instances_dir, exist_ok=True)
    work_dir = tempfile.mkdtemp(prefix=f"swe_{instance.instance_id}_", dir=instances_dir)

    try:
        # 1. Clone & checkout
        repo_path = clone_and_checkout(instance.repo, instance.base_commit, work_dir)
        if repo_path is None:
            logger.error(f"  [{instance.instance_id}] Clone failed — skipping")
            save_trajectory(output_dir / "trajs", instance.instance_id, None)
            return None

        # 2. Call agent — replace {repo_path} placeholder in system prompt
        system_prompt = SWE_BENCH_SYSTEM_PROMPT.replace("{repo_path}", repo_path)
        response = call_agent(
            api_url=api_url,
            problem_statement=instance.problem_statement,
            working_directory=repo_path,
            model=model,
            max_turns=max_turns,
            timeout=timeout,
            system_prompt=system_prompt,
        )

        # 3. Save trajectory
        save_trajectory(output_dir / "trajs", instance.instance_id, response)

        if response is None:
            logger.error(f"  [{instance.instance_id}] API call failed")
            return Prediction(
                instance_id=instance.instance_id,
                model_name_or_path=model,
                model_patch="",
            )

        # Check for error in response
        if response.get("error"):
            logger.warning(f"  [{instance.instance_id}] Agent error: {response['error']}")

        # 4. Extract patch
        patch = extract_patch(repo_path, instance.base_commit)
        patch_lines = len(patch.strip().split("\n")) if patch.strip() else 0
        logger.info(f"  [{instance.instance_id}] Patch: {patch_lines} lines")

        return Prediction(
            instance_id=instance.instance_id,
            model_name_or_path=model,
            model_patch=patch,
        )

    finally:
        # Cleanup temp directory
        try:
            shutil.rmtree(work_dir, ignore_errors=True)
        except Exception:
            pass


def generate_locate_strategies(repo: str) -> str:
    """Generate repo-specific LOCATE strategies based on repository name.
    
    # SWE-BENCH ONLY - DO NOT MERGE TO GENERAL_SYSTEM_PROMPT
    """
    base = """
## Additional LOCATE Strategies (use when grep returns too many results):

### Strategy A: Traceback-driven navigation
- Read the FULL traceback from test failure
- Navigate directly to the file:line mentioned in the LAST frame
- Read 50 lines of context around that location

### Strategy B: Import chain tracing
- Bash(command="python3 -c \\"import {module}; print({module}.__file__)\\"")
- Find the actual source file for the failing module

### Strategy C: Test-to-source reverse mapping
- Read the failing test file to understand what it imports
- Follow import statements to find the source under test
"""
    repo_lower = repo.split("/")[-1].lower() if "/" in repo else repo.lower()

    if repo_lower.startswith("django"):
        return base + """
### Django-specific:
- Errors often trace to middleware, views, or model managers
- Check django/db/models/ and django/core/ for ORM-related issues
- Bash(command="grep -rn 'class.*Manager' django/ --include='*.py' | grep -i '<keyword>'")
"""
    elif repo_lower.startswith("sympy"):
        return base + """
### SymPy-specific:
- Errors often involve symbolic computation dispatch
- Check sympy/core/ for expression evaluation issues
- SymPy uses extensive __new__/__init__ patterns - check class constructors
"""
    elif repo_lower.startswith("matplotlib"):
        return base + """
### Matplotlib-specific:
- Errors often trace to figure/axes state management
- Check lib/matplotlib/axes/ for plotting-related issues
- Bash(command="grep -rn 'def.*<func_name>' lib/matplotlib/ --include='*.py' | grep -v test")
"""
    return base


def solve_instance_multiphase(
    instance: Instance,
    api_url: str,
    model: str,
    max_turns: int,
    timeout: int,
    output_dir: Path,
) -> Optional[Prediction]:
    """Solve a single SWE-bench instance using a single continuous 60-turn session.

    Previously this used 3 separate API calls (ANALYZE→FIX→VERIFY), but the
    conversation split caused Phase 2 to fail: the model would receive a
    continuation message and immediately end_turn with 0 tool calls.

    Now uses a single call_agent invocation with 60 turns and 900s timeout.
    The System Prompt still contains phase guidance (ANALYZE→LOCATE→FIX→VERIFY)
    as a cognitive framework for the model, but no code-level phase splitting.
    """
    logger.info(f"\u2501\u2501\u2501 [{instance.instance_id}] Starting (single-session, 60 turns) \u2501\u2501\u2501")
    logger.info(f"  Repo: {instance.repo} @ {instance.base_commit[:8]}")

    instances_dir = str(output_dir / "_workdirs")
    os.makedirs(instances_dir, exist_ok=True)
    work_dir = tempfile.mkdtemp(prefix=f"swe_{instance.instance_id}_", dir=instances_dir)

    try:
        # 1. Clone & checkout
        repo_path = clone_and_checkout(instance.repo, instance.base_commit, work_dir)
        if repo_path is None:
            logger.error(f"  [{instance.instance_id}] Clone failed \u2014 skipping")
            save_trajectory(output_dir / "trajs", instance.instance_id, None)
            return None

        # 2. Build system prompt with repo_path substituted
        system_prompt = SWE_BENCH_SYSTEM_PROMPT.replace("{repo_path}", repo_path)

        # 3. Build full problem statement with reminder to edit
        locate_strategies = generate_locate_strategies(instance.repo)
        full_prompt = (
            f"Fix the following issue in the repository at {repo_path}:\n\n"
            f"{instance.problem_statement}\n\n"
            f"{locate_strategies}\n\n"
            f"Remember: You MUST make code changes using Edit or Write tools. "
            f"Reading and analyzing is not enough - you must actually fix the code."
        )

        # 4. Single API call — 60 turns, 900s
        logger.info(f"  [{instance.instance_id}] Calling agent (60 turns, 900s timeout)")
        response = call_agent(
            api_url=api_url,
            problem_statement=full_prompt,
            working_directory=repo_path,
            model=model,
            max_turns=60,
            timeout=900,
            system_prompt=system_prompt,
        )

        # 5. Handle API failure
        if response is None:
            logger.error(f"  [{instance.instance_id}] API call failed")
            save_trajectory(output_dir / "trajs", instance.instance_id, None)
            return Prediction(
                instance_id=instance.instance_id,
                model_name_or_path=model,
                model_patch="",
            )

        # Check for error in response
        if response.get("error"):
            logger.warning(f"  [{instance.instance_id}] Agent error: {response['error']}")

        # 6. Diagnostics
        stats = log_diagnostics(instance.instance_id, response)
        log_instance_summary(instance.instance_id, [stats])

        # 7. Save trajectory
        save_trajectory(output_dir / "trajs", instance.instance_id, response)

        # 8. Extract final patch
        patch = extract_patch(repo_path, instance.base_commit)
        patch_lines = len(patch.strip().split("\n")) if patch.strip() else 0
        logger.info(f"  [{instance.instance_id}] Final patch: {patch_lines} lines")

        if not patch.strip():
            logger.warning(f"  [{instance.instance_id}] \u26a0\ufe0f EMPTY PATCH - instance will score 0")

        return Prediction(
            instance_id=instance.instance_id,
            model_name_or_path=model,
            model_patch=patch,
        )
    finally:
        try:
            shutil.rmtree(work_dir, ignore_errors=True)
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(
        description="SWE-bench Harness for ZhikunCode",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--dataset", required=True, help="Path to SWE-bench JSONL file"
    )
    parser.add_argument(
        "--model", default=DEFAULT_MODEL, help=f"Model identifier (default: {DEFAULT_MODEL})"
    )
    parser.add_argument(
        "--output", default="./swe-bench/results", help="Output directory (default: ./swe-bench/results)"
    )
    parser.add_argument(
        "--workers", type=int, default=DEFAULT_WORKERS, help="Concurrency (default: 1)"
    )
    parser.add_argument(
        "--timeout", type=int, default=DEFAULT_TIMEOUT, help="Timeout seconds per instance (default: 600)"
    )
    parser.add_argument(
        "--max-turns", type=int, default=DEFAULT_MAX_TURNS, help="Max agent turns (default: 50)"
    )
    parser.add_argument(
        "--limit", type=int, default=0, help="Limit number of instances (0=all)"
    )
    parser.add_argument(
        "--api-url", default=DEFAULT_API_URL, help=f"Backend API URL (default: {DEFAULT_API_URL})"
    )
    parser.add_argument(
        "--multiphase", action="store_true", default=False,
        help="Use multi-phase solve (ANALYZE\u2192IMPLEMENT\u2192VERIFY)"
    )

    args = parser.parse_args()

    # Setup output directory (resolve to absolute to avoid relative-path issues
    # when the Java backend resolves paths relative to its own CWD)
    output_dir = Path(args.output).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "trajs").mkdir(exist_ok=True)

    # Load dataset
    instances = load_dataset(args.dataset, args.limit)
    if not instances:
        logger.error("No instances to process.")
        sys.exit(1)

    # Resumption: skip already-completed
    completed_ids = load_completed_ids(output_dir)
    pending = [i for i in instances if i.instance_id not in completed_ids]
    logger.info(f"Pending: {len(pending)} / Total: {len(instances)}")

    if not pending:
        logger.info("All instances already completed. Nothing to do.")
        sys.exit(0)

    # Health check
    if not wait_for_backend(args.api_url):
        logger.error("Backend is not available. Exiting.")
        sys.exit(1)

    # Choose solve function based on --multiphase flag
    solve_fn = solve_instance_multiphase if args.multiphase else solve_instance
    if args.multiphase:
        logger.info("Mode: multi-phase (SOLVE→VERIFY)")
    else:
        logger.info("Mode: single-phase (classic)")

    # Run
    stats = RunStats(total=len(pending))
    preds_file = output_dir / "all_preds.jsonl"
    run_start_time = time.time()

    if args.workers <= 1:
        # Sequential execution
        for instance in pending:
            pred = solve_fn(
                instance=instance,
                api_url=args.api_url,
                model=args.model,
                max_turns=args.max_turns,
                timeout=args.timeout,
                output_dir=output_dir,
            )
            _record_prediction(pred, preds_file, stats)
    else:
        # Parallel execution
        with ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = {
                executor.submit(
                    solve_fn,
                    instance=inst,
                    api_url=args.api_url,
                    model=args.model,
                    max_turns=args.max_turns,
                    timeout=args.timeout,
                    output_dir=output_dir,
                ): inst
                for inst in pending
            }
            for future in as_completed(futures):
                inst = futures[future]
                try:
                    pred = future.result()
                    _record_prediction(pred, preds_file, stats)
                except Exception as e:
                    logger.error(f"  [{inst.instance_id}] Unexpected error: {e}")
                    stats.failed += 1
                    stats.errors.append(f"{inst.instance_id}: {e}")

    # Print summary
    total_elapsed = time.time() - run_start_time
    _print_summary(stats, output_dir, total_elapsed)

    # Save run.log
    try:
        log_path = output_dir / "run.log"
        log_path.write_text(
            f"Total elapsed: {total_elapsed:.1f}s\n"
            f"Total: {stats.total}, Completed: {stats.completed}, "
            f"Failed: {stats.failed}, Empty: {stats.empty_patch}\n",
            encoding="utf-8",
        )
    except Exception:
        pass


def _record_prediction(pred: Optional[Prediction], preds_file: Path, stats: RunStats):
    """Append a prediction to the JSONL file and update stats."""
    if pred is None:
        stats.failed += 1
        return

    stats.completed += 1
    if not pred.model_patch.strip():
        stats.empty_patch += 1

    # Append to all_preds.jsonl (atomic-ish write)
    with open(preds_file, "a", encoding="utf-8") as f:
        f.write(json.dumps({
            "instance_id": pred.instance_id,
            "model_name_or_path": pred.model_name_or_path,
            "model_patch": pred.model_patch,
        }, ensure_ascii=False) + "\n")


def _print_summary(stats: RunStats, output_dir: Path, total_elapsed: float = 0.0):
    """Print run statistics."""
    logger.info("=" * 60)
    logger.info("SWE-bench Run Summary")
    logger.info("=" * 60)
    logger.info(f"  Total instances:  {stats.total}")
    logger.info(f"  Completed:        {stats.completed}")
    logger.info(f"  Failed:           {stats.failed}")
    logger.info(f"  Empty patches:    {stats.empty_patch}")
    logger.info(f"  Success rate:     {stats.completed}/{stats.total} "
                f"({100*stats.completed/max(stats.total,1):.1f}%)")
    if total_elapsed > 0:
        mins = int(total_elapsed // 60)
        secs = int(total_elapsed % 60)
        logger.info(f"  Total elapsed:    {mins}m {secs}s ({total_elapsed:.1f}s)")
    logger.info(f"  Output dir:       {output_dir.resolve()}")
    logger.info(f"  Predictions:      {output_dir / 'all_preds.jsonl'}")
    logger.info(f"  Trajectories:     {output_dir / 'trajs/'}")
    if stats.errors:
        logger.info(f"  Errors ({len(stats.errors)}):")
        for err in stats.errors[:10]:
            logger.info(f"    - {err}")
    logger.info("=" * 60)


if __name__ == "__main__":
    main()
