#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff d597f252915ddcaaa15ccdfcb35670152cb83587
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout d597f252915ddcaaa15ccdfcb35670152cb83587 tests/config/test_config.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/config/test_config.py b/tests/config/test_config.py
--- a/tests/config/test_config.py
+++ b/tests/config/test_config.py
@@ -111,6 +111,36 @@ def test_unknown_py_version(capsys: CaptureFixture) -> None:
     assert "the-newest has an invalid format, should be a version string." in output.err
 
 
+def test_regex_error(capsys: CaptureFixture) -> None:
+    """Check that we correctly error when an an option is passed whose value is an invalid regular expression."""
+    with pytest.raises(SystemExit):
+        Run(
+            [str(EMPTY_MODULE), r"--function-rgx=[\p{Han}a-z_][\p{Han}a-z0-9_]{2,30}$"],
+            exit=False,
+        )
+    output = capsys.readouterr()
+    assert (
+        r"Error in provided regular expression: [\p{Han}a-z_][\p{Han}a-z0-9_]{2,30}$ beginning at index 1: bad escape \p"
+        in output.err
+    )
+
+
+def test_csv_regex_error(capsys: CaptureFixture) -> None:
+    """Check that we correctly error when an option is passed and one
+    of its comma-separated regular expressions values is an invalid regular expression.
+    """
+    with pytest.raises(SystemExit):
+        Run(
+            [str(EMPTY_MODULE), r"--bad-names-rgx=(foo{1,3})"],
+            exit=False,
+        )
+    output = capsys.readouterr()
+    assert (
+        r"Error in provided regular expression: (foo{1 beginning at index 0: missing ), unterminated subpattern"
+        in output.err
+    )
+
+
 def test_short_verbose(capsys: CaptureFixture) -> None:
     """Check that we correctly handle the -v flag."""
     Run([str(EMPTY_MODULE), "-v"], exit=False)

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA tests/config/test_config.py
: '>>>>> End Test Output'
git checkout d597f252915ddcaaa15ccdfcb35670152cb83587 tests/config/test_config.py
