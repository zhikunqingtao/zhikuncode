#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff b17abcb09cbcee80a90f6750e0f9b53f0247656c
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout b17abcb09cbcee80a90f6750e0f9b53f0247656c sympy/core/tests/test_numbers.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_numbers.py b/sympy/core/tests/test_numbers.py
--- a/sympy/core/tests/test_numbers.py
+++ b/sympy/core/tests/test_numbers.py
@@ -1041,6 +1041,10 @@ def test_powers_Integer():
         -(-1)**Rational(2, 3)*3**Rational(2, 3)/27
     assert (-3) ** Rational(-2, 3) == \
         -(-1)**Rational(1, 3)*3**Rational(1, 3)/3
+    assert (-2) ** Rational(-10, 3) == \
+        (-1)**Rational(2, 3)*2**Rational(2, 3)/16
+    assert abs(Pow(-2, Rational(-10, 3)).n() -
+        Pow(-2, Rational(-10, 3), evaluate=False).n()) < 1e-16
 
     # negative base and rational power with some simplification
     assert (-8) ** Rational(2, 5) == \
@@ -1121,6 +1125,10 @@ def test_powers_Rational():
         -4*(-1)**Rational(2, 3)*2**Rational(1, 3)*3**Rational(2, 3)/27
     assert Rational(-3, 2)**Rational(-2, 3) == \
         -(-1)**Rational(1, 3)*2**Rational(2, 3)*3**Rational(1, 3)/3
+    assert Rational(-3, 2)**Rational(-10, 3) == \
+        8*(-1)**Rational(2, 3)*2**Rational(1, 3)*3**Rational(2, 3)/81
+    assert abs(Pow(Rational(-2, 3), Rational(-7, 4)).n() -
+        Pow(Rational(-2, 3), Rational(-7, 4), evaluate=False).n()) < 1e-16
 
     # negative integer power and negative rational base
     assert Rational(-2, 3) ** Rational(-2, 1) == Rational(9, 4)

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_numbers.py
: '>>>>> End Test Output'
git checkout b17abcb09cbcee80a90f6750e0f9b53f0247656c sympy/core/tests/test_numbers.py
