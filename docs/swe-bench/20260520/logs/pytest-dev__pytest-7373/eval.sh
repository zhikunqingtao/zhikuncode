#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 7b77fc086aab8b3a8ebc890200371884555eea1e
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 7b77fc086aab8b3a8ebc890200371884555eea1e testing/test_mark.py
git apply -v - <<'EOF_114329324912'
diff --git a/testing/test_mark.py b/testing/test_mark.py
--- a/testing/test_mark.py
+++ b/testing/test_mark.py
@@ -706,6 +706,36 @@ def test_1(parameter):
         reprec = testdir.inline_run()
         reprec.assertoutcome(skipped=1)
 
+    def test_reevaluate_dynamic_expr(self, testdir):
+        """#7360"""
+        py_file1 = testdir.makepyfile(
+            test_reevaluate_dynamic_expr1="""
+            import pytest
+
+            skip = True
+
+            @pytest.mark.skipif("skip")
+            def test_should_skip():
+                assert True
+        """
+        )
+        py_file2 = testdir.makepyfile(
+            test_reevaluate_dynamic_expr2="""
+            import pytest
+
+            skip = False
+
+            @pytest.mark.skipif("skip")
+            def test_should_not_skip():
+                assert True
+        """
+        )
+
+        file_name1 = os.path.basename(py_file1.strpath)
+        file_name2 = os.path.basename(py_file2.strpath)
+        reprec = testdir.inline_run(file_name1, file_name2)
+        reprec.assertoutcome(passed=1, skipped=1)
+
 
 class TestKeywordSelection:
     def test_select_simple(self, testdir):

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA testing/test_mark.py
: '>>>>> End Test Output'
git checkout 7b77fc086aab8b3a8ebc890200371884555eea1e testing/test_mark.py
