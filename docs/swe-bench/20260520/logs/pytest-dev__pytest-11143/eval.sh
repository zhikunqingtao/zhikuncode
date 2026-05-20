#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 6995257cf470d2143ad1683824962de4071c0eb7
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 6995257cf470d2143ad1683824962de4071c0eb7 testing/test_assertrewrite.py
git apply -v - <<'EOF_114329324912'
diff --git a/testing/test_assertrewrite.py b/testing/test_assertrewrite.py
--- a/testing/test_assertrewrite.py
+++ b/testing/test_assertrewrite.py
@@ -2042,3 +2042,17 @@ def test_max_increased_verbosity(self, pytester: Pytester) -> None:
         self.create_test_file(pytester, DEFAULT_REPR_MAX_SIZE * 10)
         result = pytester.runpytest("-vv")
         result.stdout.no_fnmatch_line("*xxx...xxx*")
+
+
+class TestIssue11140:
+    def test_constant_not_picked_as_module_docstring(self, pytester: Pytester) -> None:
+        pytester.makepyfile(
+            """\
+            0
+
+            def test_foo():
+                pass
+            """
+        )
+        result = pytester.runpytest()
+        assert result.ret == 0

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA testing/test_assertrewrite.py
: '>>>>> End Test Output'
git checkout 6995257cf470d2143ad1683824962de4071c0eb7 testing/test_assertrewrite.py
