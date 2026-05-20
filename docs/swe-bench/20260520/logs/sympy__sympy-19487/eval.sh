#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 25fbcce5b1a4c7e3956e6062930f4a44ce95a632
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 25fbcce5b1a4c7e3956e6062930f4a44ce95a632 sympy/core/tests/test_subs.py sympy/functions/elementary/tests/test_complexes.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_subs.py b/sympy/core/tests/test_subs.py
--- a/sympy/core/tests/test_subs.py
+++ b/sympy/core/tests/test_subs.py
@@ -855,3 +855,10 @@ def test_issue_17823():
 def test_issue_19326():
     x, y = [i(t) for i in map(Function, 'xy')]
     assert (x*y).subs({x: 1 + x, y: x}) == (1 + x)*x
+
+def test_issue_19558():
+    e = (7*x*cos(x) - 12*log(x)**3)*(-log(x)**4 + 2*sin(x) + 1)**2/ \
+    (2*(x*cos(x) - 2*log(x)**3)*(3*log(x)**4 - 7*sin(x) + 3)**2)
+
+    assert e.subs(x, oo) == AccumBounds(-oo, oo)
+    assert (sin(x) + cos(x)).subs(x, oo) == AccumBounds(-2, 2)
diff --git a/sympy/functions/elementary/tests/test_complexes.py b/sympy/functions/elementary/tests/test_complexes.py
--- a/sympy/functions/elementary/tests/test_complexes.py
+++ b/sympy/functions/elementary/tests/test_complexes.py
@@ -4,7 +4,7 @@
     pi, Rational, re, S, sign, sin, sqrt, Symbol, symbols, transpose,
     zoo, exp_polar, Piecewise, Interval, comp, Integral, Matrix,
     ImmutableMatrix, SparseMatrix, ImmutableSparseMatrix, MatrixSymbol,
-    FunctionMatrix, Lambda, Derivative)
+    FunctionMatrix, Lambda, Derivative, Eq)
 from sympy.core.expr import unchanged
 from sympy.core.function import ArgumentIndexError
 from sympy.testing.pytest import XFAIL, raises
@@ -296,11 +296,14 @@ def test_sign():
     assert sign(Symbol('x', real=True, zero=False)).is_nonpositive is None
 
     x, y = Symbol('x', real=True), Symbol('y')
+    f = Function('f')
     assert sign(x).rewrite(Piecewise) == \
         Piecewise((1, x > 0), (-1, x < 0), (0, True))
     assert sign(y).rewrite(Piecewise) == sign(y)
     assert sign(x).rewrite(Heaviside) == 2*Heaviside(x, H0=S(1)/2) - 1
     assert sign(y).rewrite(Heaviside) == sign(y)
+    assert sign(y).rewrite(Abs) == Piecewise((0, Eq(y, 0)), (y/Abs(y), True))
+    assert sign(f(y)).rewrite(Abs) == Piecewise((0, Eq(f(y), 0)), (f(y)/Abs(f(y)), True))
 
     # evaluate what can be evaluated
     assert sign(exp_polar(I*pi)*pi) is S.NegativeOne

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_subs.py sympy/functions/elementary/tests/test_complexes.py
: '>>>>> End Test Output'
git checkout 25fbcce5b1a4c7e3956e6062930f4a44ce95a632 sympy/core/tests/test_subs.py sympy/functions/elementary/tests/test_complexes.py
