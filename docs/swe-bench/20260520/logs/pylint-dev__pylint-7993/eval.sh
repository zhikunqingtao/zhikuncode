#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff e90702074e68e20dc8e5df5013ee3ecf22139c3e
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout e90702074e68e20dc8e5df5013ee3ecf22139c3e tests/reporters/unittest_reporting.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/reporters/unittest_reporting.py b/tests/reporters/unittest_reporting.py
--- a/tests/reporters/unittest_reporting.py
+++ b/tests/reporters/unittest_reporting.py
@@ -14,6 +14,7 @@
 from typing import TYPE_CHECKING
 
 import pytest
+from _pytest.recwarn import WarningsRecorder
 
 from pylint import checkers
 from pylint.interfaces import HIGH
@@ -88,16 +89,12 @@ def test_template_option_non_existing(linter) -> None:
     """
     output = StringIO()
     linter.reporter.out = output
-    linter.config.msg_template = (
-        "{path}:{line}:{a_new_option}:({a_second_new_option:03d})"
-    )
+    linter.config.msg_template = "{path}:{line}:{categ}:({a_second_new_option:03d})"
     linter.open()
     with pytest.warns(UserWarning) as records:
         linter.set_current_module("my_mod")
         assert len(records) == 2
-        assert (
-            "Don't recognize the argument 'a_new_option'" in records[0].message.args[0]
-        )
+        assert "Don't recognize the argument 'categ'" in records[0].message.args[0]
     assert (
         "Don't recognize the argument 'a_second_new_option'"
         in records[1].message.args[0]
@@ -113,7 +110,24 @@ def test_template_option_non_existing(linter) -> None:
     assert out_lines[2] == "my_mod:2::()"
 
 
-def test_deprecation_set_output(recwarn):
+def test_template_option_with_header(linter: PyLinter) -> None:
+    output = StringIO()
+    linter.reporter.out = output
+    linter.config.msg_template = '{{ "Category": "{category}" }}'
+    linter.open()
+    linter.set_current_module("my_mod")
+
+    linter.add_message("C0301", line=1, args=(1, 2))
+    linter.add_message(
+        "line-too-long", line=2, end_lineno=2, end_col_offset=4, args=(3, 4)
+    )
+
+    out_lines = output.getvalue().split("\n")
+    assert out_lines[1] == '{ "Category": "convention" }'
+    assert out_lines[2] == '{ "Category": "convention" }'
+
+
+def test_deprecation_set_output(recwarn: WarningsRecorder) -> None:
     """TODO remove in 3.0."""
     reporter = BaseReporter()
     # noinspection PyDeprecation

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA tests/reporters/unittest_reporting.py
: '>>>>> End Test Output'
git checkout e90702074e68e20dc8e5df5013ee3ecf22139c3e tests/reporters/unittest_reporting.py
