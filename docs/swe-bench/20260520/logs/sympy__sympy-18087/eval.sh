#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 9da013ad0ddc3cd96fe505f2e47c63e372040916
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 9da013ad0ddc3cd96fe505f2e47c63e372040916 sympy/core/tests/test_exprtools.py sympy/simplify/tests/test_fu.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_exprtools.py b/sympy/core/tests/test_exprtools.py
--- a/sympy/core/tests/test_exprtools.py
+++ b/sympy/core/tests/test_exprtools.py
@@ -27,6 +27,8 @@ def test_Factors():
     assert Factors({x: 2, y: 3, sin(x): 4}).as_expr() == x**2*y**3*sin(x)**4
     assert Factors(S.Infinity) == Factors({oo: 1})
     assert Factors(S.NegativeInfinity) == Factors({oo: 1, -1: 1})
+    # issue #18059:
+    assert Factors((x**2)**S.Half).as_expr() == (x**2)**S.Half
 
     a = Factors({x: 5, y: 3, z: 7})
     b = Factors({      y: 4, z: 3, t: 10})
diff --git a/sympy/simplify/tests/test_fu.py b/sympy/simplify/tests/test_fu.py
--- a/sympy/simplify/tests/test_fu.py
+++ b/sympy/simplify/tests/test_fu.py
@@ -276,6 +276,9 @@ def test_fu():
     expr = Mul(*[cos(2**i) for i in range(10)])
     assert fu(expr) == sin(1024)/(1024*sin(1))
 
+    # issue #18059:
+    assert fu(cos(x) + sqrt(sin(x)**2)) == cos(x) + sqrt(sin(x)**2)
+
 
 def test_objective():
     assert fu(sin(x)/cos(x), measure=lambda x: x.count_ops()) == \

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_exprtools.py sympy/simplify/tests/test_fu.py
: '>>>>> End Test Output'
git checkout 9da013ad0ddc3cd96fe505f2e47c63e372040916 sympy/core/tests/test_exprtools.py sympy/simplify/tests/test_fu.py
