#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 748ce73479ee2cd5c861431091001cc18943c735
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 748ce73479ee2cd5c861431091001cc18943c735 sympy/assumptions/tests/test_refine.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/assumptions/tests/test_refine.py b/sympy/assumptions/tests/test_refine.py
--- a/sympy/assumptions/tests/test_refine.py
+++ b/sympy/assumptions/tests/test_refine.py
@@ -1,5 +1,5 @@
 from sympy import (Abs, exp, Expr, I, pi, Q, Rational, refine, S, sqrt,
-                   atan, atan2, nan, Symbol, re, im, sign)
+                   atan, atan2, nan, Symbol, re, im, sign, arg)
 from sympy.abc import w, x, y, z
 from sympy.core.relational import Eq, Ne
 from sympy.functions.elementary.piecewise import Piecewise
@@ -160,6 +160,10 @@ def test_sign():
     x = Symbol('x', complex=True)
     assert refine(sign(x), Q.zero(x)) == 0
 
+def test_arg():
+    x = Symbol('x', complex = True)
+    assert refine(arg(x), Q.positive(x)) == 0
+    assert refine(arg(x), Q.negative(x)) == pi
 
 def test_func_args():
     class MyClass(Expr):

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/assumptions/tests/test_refine.py
: '>>>>> End Test Output'
git checkout 748ce73479ee2cd5c861431091001cc18943c735 sympy/assumptions/tests/test_refine.py
