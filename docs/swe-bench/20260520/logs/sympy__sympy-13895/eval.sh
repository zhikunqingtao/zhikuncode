#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 4da0b64558e9551a11a99bccc63557ba34f50c58
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 4da0b64558e9551a11a99bccc63557ba34f50c58 sympy/core/tests/test_numbers.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_numbers.py b/sympy/core/tests/test_numbers.py
--- a/sympy/core/tests/test_numbers.py
+++ b/sympy/core/tests/test_numbers.py
@@ -1021,6 +1021,12 @@ def test_powers_Integer():
     assert (-3) ** Rational(-2, 3) == \
         -(-1)**Rational(1, 3)*3**Rational(1, 3)/3
 
+    # negative base and rational power with some simplification
+    assert (-8) ** Rational(2, 5) == \
+        2*(-1)**Rational(2, 5)*2**Rational(1, 5)
+    assert (-4) ** Rational(9, 5) == \
+        -8*(-1)**Rational(4, 5)*2**Rational(3, 5)
+
     assert S(1234).factors() == {617: 1, 2: 1}
     assert Rational(2*3, 3*5*7).factors() == {2: 1, 5: -1, 7: -1}
 
@@ -1194,6 +1200,14 @@ def test_issue_3449():
     assert sqrt(x - 1).subs(x, 5) == 2
 
 
+def test_issue_13890():
+    x = Symbol("x")
+    e = (-x/4 - S(1)/12)**x - 1
+    f = simplify(e)
+    a = S(9)/5
+    assert abs(e.subs(x,a).evalf() - f.subs(x,a).evalf()) < 1e-15
+
+
 def test_Integer_factors():
     def F(i):
         return Integer(i).factors()

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_numbers.py
: '>>>>> End Test Output'
git checkout 4da0b64558e9551a11a99bccc63557ba34f50c58 sympy/core/tests/test_numbers.py
