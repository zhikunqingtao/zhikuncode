#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff ab864967e71c950a15771bb6c3723636026ba876
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout ab864967e71c950a15771bb6c3723636026ba876 sympy/core/tests/test_arit.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_arit.py b/sympy/core/tests/test_arit.py
--- a/sympy/core/tests/test_arit.py
+++ b/sympy/core/tests/test_arit.py
@@ -374,12 +374,10 @@ def test_Mul_doesnt_expand_exp():
     assert (x**(-log(5)/log(3))*x)/(x*x**( - log(5)/log(3))) == sympify(1)
 
 def test_Mul_is_integer():
-
     k = Symbol('k', integer=True)
     n = Symbol('n', integer=True)
     nr = Symbol('nr', rational=False)
     nz = Symbol('nz', integer=True, zero=False)
-    nze = Symbol('nze', even=True, zero=False)
     e = Symbol('e', even=True)
     o = Symbol('o', odd=True)
     i2 = Symbol('2', prime=True, even=True)
@@ -388,18 +386,31 @@ def test_Mul_is_integer():
     assert (nz/3).is_integer is None
     assert (nr/3).is_integer is False
     assert (x*k*n).is_integer is None
+    assert (e/2).is_integer is True
+    assert (e**2/2).is_integer is True
+    assert (2/k).is_integer is None
+    assert (2/k**2).is_integer is None
+    assert ((-1)**k*n).is_integer is True
+    assert (3*k*e/2).is_integer is True
+    assert (2*k*e/3).is_integer is None
     assert (e/o).is_integer is None
     assert (o/e).is_integer is False
     assert (o/i2).is_integer is False
-    assert Mul(o, 1/o, evaluate=False).is_integer is True
     assert Mul(k, 1/k, evaluate=False).is_integer is None
-    assert Mul(nze, 1/nze, evaluate=False).is_integer is True
-    assert Mul(2., S.Half, evaluate=False).is_integer is False
+    assert Mul(2., S.Half, evaluate=False).is_integer is None
+    assert (2*sqrt(k)).is_integer is None
+    assert (2*k**n).is_integer is None
 
     s = 2**2**2**Pow(2, 1000, evaluate=False)
     m = Mul(s, s, evaluate=False)
     assert m.is_integer
 
+    # broken in 1.6 and before, see #20161
+    xq = Symbol('xq', rational=True)
+    yq = Symbol('yq', rational=True)
+    assert (xq*yq).is_integer is None
+    e_20161 = Mul(-1,Mul(1,Pow(2,-1,evaluate=False),evaluate=False),evaluate=False)
+    assert e_20161.is_integer is not True # expand(e_20161) -> -1/2, but no need to see that in the assumption without evaluation
 
 def test_Add_Mul_is_integer():
     x = Symbol('x')

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_arit.py
: '>>>>> End Test Output'
git checkout ab864967e71c950a15771bb6c3723636026ba876 sympy/core/tests/test_arit.py
