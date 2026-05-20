#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff f91de695585c1fbc7d4f49ee061f64fcb1c2c4d8
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout f91de695585c1fbc7d4f49ee061f64fcb1c2c4d8 sympy/printing/tests/test_numpy.py sympy/printing/tests/test_pycode.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_numpy.py b/sympy/printing/tests/test_numpy.py
--- a/sympy/printing/tests/test_numpy.py
+++ b/sympy/printing/tests/test_numpy.py
@@ -1,6 +1,6 @@
 from sympy import (
     Piecewise, lambdify, Equality, Unequality, Sum, Mod, cbrt, sqrt,
-    MatrixSymbol, BlockMatrix
+    MatrixSymbol, BlockMatrix, Identity
 )
 from sympy import eye
 from sympy.abc import x, i, j, a, b, c, d
@@ -11,7 +11,7 @@
 from sympy.printing.lambdarepr import NumPyPrinter
 
 from sympy.utilities.pytest import warns_deprecated_sympy
-from sympy.utilities.pytest import skip
+from sympy.utilities.pytest import skip, raises
 from sympy.external import import_module
 
 np = import_module('numpy')
@@ -252,3 +252,21 @@ def test_16857():
 
     printer = NumPyPrinter()
     assert printer.doprint(A) == 'numpy.block([[a_1, a_2], [a_3, a_4]])'
+
+
+def test_issue_17006():
+    if not np:
+        skip("NumPy not installed")
+
+    M = MatrixSymbol("M", 2, 2)
+
+    f = lambdify(M, M + Identity(2))
+    ma = np.array([[1, 2], [3, 4]])
+    mr = np.array([[2, 2], [3, 5]])
+
+    assert (f(ma) == mr).all()
+
+    from sympy import symbols
+    n = symbols('n', integer=True)
+    N = MatrixSymbol("M", n, n)
+    raises(NotImplementedError, lambda: lambdify(N, N + Identity(n)))
diff --git a/sympy/printing/tests/test_pycode.py b/sympy/printing/tests/test_pycode.py
--- a/sympy/printing/tests/test_pycode.py
+++ b/sympy/printing/tests/test_pycode.py
@@ -7,7 +7,7 @@
 from sympy.core.numbers import pi
 from sympy.functions import acos, Piecewise, sign
 from sympy.logic import And, Or
-from sympy.matrices import SparseMatrix, MatrixSymbol
+from sympy.matrices import SparseMatrix, MatrixSymbol, Identity
 from sympy.printing.pycode import (
     MpmathPrinter, NumPyPrinter, PythonCodePrinter, pycode, SciPyPrinter
 )
@@ -49,6 +49,7 @@ def test_NumPyPrinter():
     A = MatrixSymbol("A", 2, 2)
     assert p.doprint(A**(-1)) == "numpy.linalg.inv(A)"
     assert p.doprint(A**5) == "numpy.linalg.matrix_power(A, 5)"
+    assert p.doprint(Identity(3)) == "numpy.eye(3)"
 
 
 def test_SciPyPrinter():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_numpy.py sympy/printing/tests/test_pycode.py
: '>>>>> End Test Output'
git checkout f91de695585c1fbc7d4f49ee061f64fcb1c2c4d8 sympy/printing/tests/test_numpy.py sympy/printing/tests/test_pycode.py
