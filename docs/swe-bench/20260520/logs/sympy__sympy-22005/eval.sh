#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 2c83657ff1c62fc2761b639469fdac7f7561a72a
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 2c83657ff1c62fc2761b639469fdac7f7561a72a sympy/solvers/tests/test_polysys.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/solvers/tests/test_polysys.py b/sympy/solvers/tests/test_polysys.py
--- a/sympy/solvers/tests/test_polysys.py
+++ b/sympy/solvers/tests/test_polysys.py
@@ -49,6 +49,11 @@ def test_solve_poly_system():
         [z, -2*x*y**2 + x + y**2*z, y**2*(-z - 4) + 2]))
     raises(PolynomialError, lambda: solve_poly_system([1/x], x))
 
+    raises(NotImplementedError, lambda: solve_poly_system(
+          [x-1,], (x, y)))
+    raises(NotImplementedError, lambda: solve_poly_system(
+          [y-1,], (x, y)))
+
 
 def test_solve_biquadratic():
     x0, y0, x1, y1, r = symbols('x0 y0 x1 y1 r')

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/solvers/tests/test_polysys.py
: '>>>>> End Test Output'
git checkout 2c83657ff1c62fc2761b639469fdac7f7561a72a sympy/solvers/tests/test_polysys.py
