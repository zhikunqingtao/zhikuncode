#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 9ef28fba5b4d6d0168237c9c005a550e6dc27d81
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 9ef28fba5b4d6d0168237c9c005a550e6dc27d81 sympy/simplify/tests/test_trigsimp.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/simplify/tests/test_trigsimp.py b/sympy/simplify/tests/test_trigsimp.py
--- a/sympy/simplify/tests/test_trigsimp.py
+++ b/sympy/simplify/tests/test_trigsimp.py
@@ -1,7 +1,8 @@
 from sympy import (
     symbols, sin, simplify, cos, trigsimp, rad, tan, exptrigsimp,sinh,
     cosh, diff, cot, Subs, exp, tanh, exp, S, integrate, I,Matrix,
-    Symbol, coth, pi, log, count_ops, sqrt, E, expand, Piecewise)
+    Symbol, coth, pi, log, count_ops, sqrt, E, expand, Piecewise , Rational
+    )
 
 from sympy.core.compatibility import long
 from sympy.utilities.pytest import XFAIL
@@ -357,6 +358,14 @@ def test_issue_2827_trigsimp_methods():
     eq = 1/sqrt(E) + E
     assert exptrigsimp(eq) == eq
 
+def test_issue_15129_trigsimp_methods():
+    t1 = Matrix([sin(Rational(1, 50)), cos(Rational(1, 50)), 0])
+    t2 = Matrix([sin(Rational(1, 25)), cos(Rational(1, 25)), 0])
+    t3 = Matrix([cos(Rational(1, 25)), sin(Rational(1, 25)), 0])
+    r1 = t1.dot(t2)
+    r2 = t1.dot(t3)
+    assert trigsimp(r1) == cos(S(1)/50)
+    assert trigsimp(r2) == sin(S(3)/50)
 
 def test_exptrigsimp():
     def valid(a, b):

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/simplify/tests/test_trigsimp.py
: '>>>>> End Test Output'
git checkout 9ef28fba5b4d6d0168237c9c005a550e6dc27d81 sympy/simplify/tests/test_trigsimp.py
