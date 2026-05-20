#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff ca6ef27272be31c9dc3753ede9232c39df9a75d8
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout ca6ef27272be31c9dc3753ede9232c39df9a75d8 sympy/printing/tests/test_mathematica.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_mathematica.py b/sympy/printing/tests/test_mathematica.py
--- a/sympy/printing/tests/test_mathematica.py
+++ b/sympy/printing/tests/test_mathematica.py
@@ -1,5 +1,5 @@
 from sympy.core import (S, pi, oo, symbols, Function,
-                        Rational, Integer, Tuple)
+                        Rational, Integer, Tuple, Derivative)
 from sympy.integrals import Integral
 from sympy.concrete import Sum
 from sympy.functions import exp, sin, cos
@@ -74,6 +74,14 @@ def test_Integral():
         "{y, -Infinity, Infinity}]]"
 
 
+def test_Derivative():
+    assert mcode(Derivative(sin(x), x)) == "Hold[D[Sin[x], x]]"
+    assert mcode(Derivative(x, x)) == "Hold[D[x, x]]"
+    assert mcode(Derivative(sin(x)*y**4, x, 2)) == "Hold[D[y^4*Sin[x], x, x]]"
+    assert mcode(Derivative(sin(x)*y**4, x, y, x)) == "Hold[D[y^4*Sin[x], x, y, x]]"
+    assert mcode(Derivative(sin(x)*y**4, x, y, 3, x)) == "Hold[D[y^4*Sin[x], x, y, y, y, x]]"
+
+
 def test_Sum():
     assert mcode(Sum(sin(x), (x, 0, 10))) == "Hold[Sum[Sin[x], {x, 0, 10}]]"
     assert mcode(Sum(exp(-x**2 - y**2),

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_mathematica.py
: '>>>>> End Test Output'
git checkout ca6ef27272be31c9dc3753ede9232c39df9a75d8 sympy/printing/tests/test_mathematica.py
