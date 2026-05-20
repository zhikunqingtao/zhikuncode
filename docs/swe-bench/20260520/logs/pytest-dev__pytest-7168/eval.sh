#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 4787fd64a4ca0dba5528b5651bddd254102fe9f3
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 4787fd64a4ca0dba5528b5651bddd254102fe9f3 testing/io/test_saferepr.py
git apply -v - <<'EOF_114329324912'
diff --git a/testing/io/test_saferepr.py b/testing/io/test_saferepr.py
--- a/testing/io/test_saferepr.py
+++ b/testing/io/test_saferepr.py
@@ -154,3 +154,20 @@ def test_pformat_dispatch():
     assert _pformat_dispatch("a") == "'a'"
     assert _pformat_dispatch("a" * 10, width=5) == "'aaaaaaaaaa'"
     assert _pformat_dispatch("foo bar", width=5) == "('foo '\n 'bar')"
+
+
+def test_broken_getattribute():
+    """saferepr() can create proper representations of classes with
+    broken __getattribute__ (#7145)
+    """
+
+    class SomeClass:
+        def __getattribute__(self, attr):
+            raise RuntimeError
+
+        def __repr__(self):
+            raise RuntimeError
+
+    assert saferepr(SomeClass()).startswith(
+        "<[RuntimeError() raised in repr()] SomeClass object at 0x"
+    )

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA testing/io/test_saferepr.py
: '>>>>> End Test Output'
git checkout 4787fd64a4ca0dba5528b5651bddd254102fe9f3 testing/io/test_saferepr.py
