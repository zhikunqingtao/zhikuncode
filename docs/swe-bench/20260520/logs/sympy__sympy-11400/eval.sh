#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 8dcb12a6cf500e8738d6729ab954a261758f49ca
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 8dcb12a6cf500e8738d6729ab954a261758f49ca sympy/printing/tests/test_ccode.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_ccode.py b/sympy/printing/tests/test_ccode.py
--- a/sympy/printing/tests/test_ccode.py
+++ b/sympy/printing/tests/test_ccode.py
@@ -120,6 +120,16 @@ def test_ccode_boolean():
     assert ccode((x | y) & z) == "z && (x || y)"
 
 
+def test_ccode_Relational():
+    from sympy import Eq, Ne, Le, Lt, Gt, Ge
+    assert ccode(Eq(x, y)) == "x == y"
+    assert ccode(Ne(x, y)) == "x != y"
+    assert ccode(Le(x, y)) == "x <= y"
+    assert ccode(Lt(x, y)) == "x < y"
+    assert ccode(Gt(x, y)) == "x > y"
+    assert ccode(Ge(x, y)) == "x >= y"
+
+
 def test_ccode_Piecewise():
     expr = Piecewise((x, x < 1), (x**2, True))
     assert ccode(expr) == (
@@ -162,6 +172,18 @@ def test_ccode_Piecewise():
     raises(ValueError, lambda: ccode(expr))
 
 
+def test_ccode_sinc():
+    from sympy import sinc
+    expr = sinc(x)
+    assert ccode(expr) == (
+            "((x != 0) ? (\n"
+            "   sin(x)/x\n"
+            ")\n"
+            ": (\n"
+            "   1\n"
+            "))")
+
+
 def test_ccode_Piecewise_deep():
     p = ccode(2*Piecewise((x, x < 1), (x + 1, x < 2), (x**2, True)))
     assert p == (

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_ccode.py
: '>>>>> End Test Output'
git checkout 8dcb12a6cf500e8738d6729ab954a261758f49ca sympy/printing/tests/test_ccode.py
