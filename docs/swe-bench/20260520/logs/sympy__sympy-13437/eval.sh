#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 674afc619d7f5c519b6a5393a8b0532a131e57e0
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 674afc619d7f5c519b6a5393a8b0532a131e57e0 sympy/functions/combinatorial/tests/test_comb_numbers.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/functions/combinatorial/tests/test_comb_numbers.py b/sympy/functions/combinatorial/tests/test_comb_numbers.py
--- a/sympy/functions/combinatorial/tests/test_comb_numbers.py
+++ b/sympy/functions/combinatorial/tests/test_comb_numbers.py
@@ -73,6 +73,11 @@ def test_bell():
     assert bell(1, x) == x
     assert bell(2, x) == x**2 + x
     assert bell(5, x) == x**5 + 10*x**4 + 25*x**3 + 15*x**2 + x
+    assert bell(oo) == S.Infinity
+    raises(ValueError, lambda: bell(oo, x))
+
+    raises(ValueError, lambda: bell(-1))
+    raises(ValueError, lambda: bell(S(1)/2))
 
     X = symbols('x:6')
     # X = (x0, x1, .. x5)
@@ -99,9 +104,9 @@ def test_bell():
     for i in [0, 2, 3, 7, 13, 42, 55]:
         assert bell(i).evalf() == bell(n).rewrite(Sum).evalf(subs={n: i})
 
-    # For negative numbers, the formula does not hold
-    m = Symbol('m', integer=True)
-    assert bell(-1).evalf() == bell(m).rewrite(Sum).evalf(subs={m: -1})
+    # issue 9184
+    n = Dummy('n')
+    assert bell(n).limit(n, S.Infinity) == S.Infinity
 
 
 def test_harmonic():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/functions/combinatorial/tests/test_comb_numbers.py
: '>>>>> End Test Output'
git checkout 674afc619d7f5c519b6a5393a8b0532a131e57e0 sympy/functions/combinatorial/tests/test_comb_numbers.py
