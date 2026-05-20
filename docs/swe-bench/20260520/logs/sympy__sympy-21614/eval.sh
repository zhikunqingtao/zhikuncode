#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff b4777fdcef467b7132c055f8ac2c9a5059e6a145
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout b4777fdcef467b7132c055f8ac2c9a5059e6a145 sympy/core/tests/test_kind.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_kind.py b/sympy/core/tests/test_kind.py
--- a/sympy/core/tests/test_kind.py
+++ b/sympy/core/tests/test_kind.py
@@ -5,6 +5,7 @@
 from sympy.core.singleton import S
 from sympy.core.symbol import Symbol
 from sympy.integrals.integrals import Integral
+from sympy.core.function import Derivative
 from sympy.matrices import (Matrix, SparseMatrix, ImmutableMatrix,
     ImmutableSparseMatrix, MatrixSymbol, MatrixKind, MatMul)
 
@@ -39,6 +40,11 @@ def test_Integral_kind():
     assert Integral(comm_x, comm_x).kind is NumberKind
     assert Integral(A, comm_x).kind is MatrixKind(NumberKind)
 
+def test_Derivative_kind():
+    A = MatrixSymbol('A', 2,2)
+    assert Derivative(comm_x, comm_x).kind is NumberKind
+    assert Derivative(A, comm_x).kind is MatrixKind(NumberKind)
+
 def test_Matrix_kind():
     classes = (Matrix, SparseMatrix, ImmutableMatrix, ImmutableSparseMatrix)
     for cls in classes:

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_kind.py
: '>>>>> End Test Output'
git checkout b4777fdcef467b7132c055f8ac2c9a5059e6a145 sympy/core/tests/test_kind.py
