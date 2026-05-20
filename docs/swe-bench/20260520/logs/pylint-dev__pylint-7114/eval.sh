#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 397c1703e8ae6349d33f7b99f45b2ccaf581e666
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 397c1703e8ae6349d33f7b99f45b2ccaf581e666 tests/checkers/unittest_imports.py tests/lint/unittest_lint.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/checkers/unittest_imports.py b/tests/checkers/unittest_imports.py
--- a/tests/checkers/unittest_imports.py
+++ b/tests/checkers/unittest_imports.py
@@ -7,6 +7,7 @@
 import os
 
 import astroid
+import pytest
 
 from pylint import epylint as lint
 from pylint.checkers import imports
@@ -40,6 +41,9 @@ def test_relative_beyond_top_level(self) -> None:
             self.checker.visit_importfrom(module.body[2].body[0])
 
     @staticmethod
+    @pytest.mark.xfail(
+        reason="epylint manipulates cwd; these tests should not be using epylint"
+    )
     def test_relative_beyond_top_level_two() -> None:
         output, errors = lint.py_run(
             f"{os.path.join(REGR_DATA, 'beyond_top_two')} -d all -e relative-beyond-top-level",
diff --git a/tests/lint/unittest_lint.py b/tests/lint/unittest_lint.py
--- a/tests/lint/unittest_lint.py
+++ b/tests/lint/unittest_lint.py
@@ -942,3 +942,12 @@ def test_lint_namespace_package_under_dir(initialized_linter: PyLinter) -> None:
         create_files(["outer/namespace/__init__.py", "outer/namespace/module.py"])
         linter.check(["outer.namespace"])
     assert not linter.stats.by_msg
+
+
+def test_identically_named_nested_module(initialized_linter: PyLinter) -> None:
+    with tempdir():
+        create_files(["identical/identical.py"])
+        with open("identical/identical.py", "w", encoding="utf-8") as f:
+            f.write("import imp")
+        initialized_linter.check(["identical"])
+    assert initialized_linter.stats.by_msg["deprecated-module"] == 1

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA tests/checkers/unittest_imports.py tests/lint/unittest_lint.py
: '>>>>> End Test Output'
git checkout 397c1703e8ae6349d33f7b99f45b2ccaf581e666 tests/checkers/unittest_imports.py tests/lint/unittest_lint.py
