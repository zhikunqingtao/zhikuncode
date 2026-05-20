#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff d60497958f6dea7f5e25bc41e9107a6a63694d01
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout d60497958f6dea7f5e25bc41e9107a6a63694d01 sympy/polys/tests/test_partfrac.py sympy/polys/tests/test_polytools.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/polys/tests/test_partfrac.py b/sympy/polys/tests/test_partfrac.py
--- a/sympy/polys/tests/test_partfrac.py
+++ b/sympy/polys/tests/test_partfrac.py
@@ -8,7 +8,7 @@
 )
 
 from sympy import (S, Poly, E, pi, I, Matrix, Eq, RootSum, Lambda,
-                   Symbol, Dummy, factor, together, sqrt, Expr)
+                   Symbol, Dummy, factor, together, sqrt, Expr, Rational)
 from sympy.utilities.pytest import raises, XFAIL
 from sympy.abc import x, y, a, b, c
 
@@ -37,6 +37,18 @@ def test_apart():
 
     assert apart(Eq((x**2 + 1)/(x + 1), x), x) == Eq(x - 1 + 2/(x + 1), x)
 
+    assert apart(x/2, y) == x/2
+
+    f, g = (x+y)/(2*x - y), Rational(3/2)*y/((2*x - y)) + Rational(1/2)
+
+    assert apart(f, x, full=False) == g
+    assert apart(f, x, full=True) == g
+
+    f, g = (x+y)/(2*x - y), 3*x/(2*x - y) - 1
+
+    assert apart(f, y, full=False) == g
+    assert apart(f, y, full=True) == g
+
     raises(NotImplementedError, lambda: apart(1/(x + 1)/(y + 2)))
 
 
diff --git a/sympy/polys/tests/test_polytools.py b/sympy/polys/tests/test_polytools.py
--- a/sympy/polys/tests/test_polytools.py
+++ b/sympy/polys/tests/test_polytools.py
@@ -1700,6 +1700,10 @@ def test_div():
     q = f.exquo(g)
     assert q.get_domain().is_ZZ
 
+    f, g = Poly(x+y, x), Poly(2*x+y, x)
+    q, r = f.div(g)
+    assert q.get_domain().is_Frac and r.get_domain().is_Frac
+
 
 def test_gcdex():
     f, g = 2*x, x**2 - 16

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/polys/tests/test_partfrac.py sympy/polys/tests/test_polytools.py
: '>>>>> End Test Output'
git checkout d60497958f6dea7f5e25bc41e9107a6a63694d01 sympy/polys/tests/test_partfrac.py sympy/polys/tests/test_polytools.py
