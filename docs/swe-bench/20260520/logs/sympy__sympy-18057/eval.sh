#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 62000f37b8821573ba00280524ffb4ac4a380875
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 62000f37b8821573ba00280524ffb4ac4a380875 sympy/core/tests/test_expr.py sympy/core/tests/test_var.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_expr.py b/sympy/core/tests/test_expr.py
--- a/sympy/core/tests/test_expr.py
+++ b/sympy/core/tests/test_expr.py
@@ -1903,3 +1903,24 @@ def test_ExprBuilder():
     eb = ExprBuilder(Mul)
     eb.args.extend([x, x])
     assert eb.build() == x**2
+
+def test_non_string_equality():
+    # Expressions should not compare equal to strings
+    x = symbols('x')
+    one = sympify(1)
+    assert (x == 'x') is False
+    assert (x != 'x') is True
+    assert (one == '1') is False
+    assert (one != '1') is True
+    assert (x + 1 == 'x + 1') is False
+    assert (x + 1 != 'x + 1') is True
+
+    # Make sure == doesn't try to convert the resulting expression to a string
+    # (e.g., by calling sympify() instead of _sympify())
+
+    class BadRepr(object):
+        def __repr__(self):
+            raise RuntimeError
+
+    assert (x == BadRepr()) is False
+    assert (x != BadRepr()) is True
diff --git a/sympy/core/tests/test_var.py b/sympy/core/tests/test_var.py
--- a/sympy/core/tests/test_var.py
+++ b/sympy/core/tests/test_var.py
@@ -19,7 +19,8 @@ def test_var():
     assert ns['fg'] == Symbol('fg')
 
 # check return value
-    assert v == ['d', 'e', 'fg']
+    assert v != ['d', 'e', 'fg']
+    assert v == [Symbol('d'), Symbol('e'), Symbol('fg')]
 
 
 def test_var_return():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_expr.py sympy/core/tests/test_var.py
: '>>>>> End Test Output'
git checkout 62000f37b8821573ba00280524ffb4ac4a380875 sympy/core/tests/test_expr.py sympy/core/tests/test_var.py
