#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 5c1644ff85e15752f9f8721bc142bfbf975e7805
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 5c1644ff85e15752f9f8721bc142bfbf975e7805 sympy/core/tests/test_arit.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_arit.py b/sympy/core/tests/test_arit.py
--- a/sympy/core/tests/test_arit.py
+++ b/sympy/core/tests/test_arit.py
@@ -1,7 +1,7 @@
 from __future__ import division
 
 from sympy import (Basic, Symbol, sin, cos, exp, sqrt, Rational, Float, re, pi,
-        sympify, Add, Mul, Pow, Mod, I, log, S, Max, symbols, oo, Integer,
+        sympify, Add, Mul, Pow, Mod, I, log, S, Max, symbols, oo, zoo, Integer,
         sign, im, nan, Dummy, factorial, comp, refine
 )
 from sympy.core.compatibility import long, range
@@ -1937,6 +1937,14 @@ def test_Mul_with_zero_infinite():
     assert e.is_positive is None
     assert e.is_hermitian is None
 
+def test_Mul_does_not_cancel_infinities():
+    a, b = symbols('a b')
+    assert ((zoo + 3*a)/(3*a + zoo)) is nan
+    assert ((b - oo)/(b - oo)) is nan
+    # issue 13904
+    expr = (1/(a+b) + 1/(a-b))/(1/(a+b) - 1/(a-b))
+    assert expr.subs(b, a) is nan
+
 def test_issue_8247_8354():
     from sympy import tan
     z = sqrt(1 + sqrt(3)) + sqrt(3 + 3*sqrt(3)) - sqrt(10 + 6*sqrt(3))

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_arit.py
: '>>>>> End Test Output'
git checkout 5c1644ff85e15752f9f8721bc142bfbf975e7805 sympy/core/tests/test_arit.py
