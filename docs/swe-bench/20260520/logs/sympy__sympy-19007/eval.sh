#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff f9e030b57623bebdc2efa7f297c1b5ede08fcebf
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout f9e030b57623bebdc2efa7f297c1b5ede08fcebf sympy/matrices/expressions/tests/test_blockmatrix.py sympy/matrices/expressions/tests/test_indexing.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/matrices/expressions/tests/test_blockmatrix.py b/sympy/matrices/expressions/tests/test_blockmatrix.py
--- a/sympy/matrices/expressions/tests/test_blockmatrix.py
+++ b/sympy/matrices/expressions/tests/test_blockmatrix.py
@@ -192,7 +192,6 @@ def test_BlockDiagMatrix():
 def test_blockcut():
     A = MatrixSymbol('A', n, m)
     B = blockcut(A, (n/2, n/2), (m/2, m/2))
-    assert A[i, j] == B[i, j]
     assert B == BlockMatrix([[A[:n/2, :m/2], A[:n/2, m/2:]],
                              [A[n/2:, :m/2], A[n/2:, m/2:]]])
 
diff --git a/sympy/matrices/expressions/tests/test_indexing.py b/sympy/matrices/expressions/tests/test_indexing.py
--- a/sympy/matrices/expressions/tests/test_indexing.py
+++ b/sympy/matrices/expressions/tests/test_indexing.py
@@ -1,7 +1,7 @@
 from sympy import (symbols, MatrixSymbol, MatPow, BlockMatrix, KroneckerDelta,
         Identity, ZeroMatrix, ImmutableMatrix, eye, Sum, Dummy, trace,
         Symbol)
-from sympy.testing.pytest import raises
+from sympy.testing.pytest import raises, XFAIL
 from sympy.matrices.expressions.matexpr import MatrixElement, MatrixExpr
 
 k, l, m, n = symbols('k l m n', integer=True)
@@ -83,6 +83,72 @@ def test_block_index():
     assert BI.as_explicit().equals(eye(6))
 
 
+def test_block_index_symbolic():
+    # Note that these matrices may be zero-sized and indices may be negative, which causes
+    # all naive simplifications given in the comments to be invalid
+    A1 = MatrixSymbol('A1', n, k)
+    A2 = MatrixSymbol('A2', n, l)
+    A3 = MatrixSymbol('A3', m, k)
+    A4 = MatrixSymbol('A4', m, l)
+    A = BlockMatrix([[A1, A2], [A3, A4]])
+    assert A[0, 0] == MatrixElement(A, 0, 0)  # Cannot be A1[0, 0]
+    assert A[n - 1, k - 1] == A1[n - 1, k - 1]
+    assert A[n, k] == A4[0, 0]
+    assert A[n + m - 1, 0] == MatrixElement(A, n + m - 1, 0)  # Cannot be A3[m - 1, 0]
+    assert A[0, k + l - 1] == MatrixElement(A, 0, k + l - 1)  # Cannot be A2[0, l - 1]
+    assert A[n + m - 1, k + l - 1] == MatrixElement(A, n + m - 1, k + l - 1)  # Cannot be A4[m - 1, l - 1]
+    assert A[i, j] == MatrixElement(A, i, j)
+    assert A[n + i, k + j] == MatrixElement(A, n + i, k + j)  # Cannot be A4[i, j]
+    assert A[n - i - 1, k - j - 1] == MatrixElement(A, n - i - 1, k - j - 1)  # Cannot be A1[n - i - 1, k - j - 1]
+
+
+def test_block_index_symbolic_nonzero():
+    # All invalid simplifications from test_block_index_symbolic() that become valid if all
+    # matrices have nonzero size and all indices are nonnegative
+    k, l, m, n = symbols('k l m n', integer=True, positive=True)
+    i, j = symbols('i j', integer=True, nonnegative=True)
+    A1 = MatrixSymbol('A1', n, k)
+    A2 = MatrixSymbol('A2', n, l)
+    A3 = MatrixSymbol('A3', m, k)
+    A4 = MatrixSymbol('A4', m, l)
+    A = BlockMatrix([[A1, A2], [A3, A4]])
+    assert A[0, 0] == A1[0, 0]
+    assert A[n + m - 1, 0] == A3[m - 1, 0]
+    assert A[0, k + l - 1] == A2[0, l - 1]
+    assert A[n + m - 1, k + l - 1] == A4[m - 1, l - 1]
+    assert A[i, j] == MatrixElement(A, i, j)
+    assert A[n + i, k + j] == A4[i, j]
+    assert A[n - i - 1, k - j - 1] == A1[n - i - 1, k - j - 1]
+    assert A[2 * n, 2 * k] == A4[n, k]
+
+
+def test_block_index_large():
+    n, m, k = symbols('n m k', integer=True, positive=True)
+    i = symbols('i', integer=True, nonnegative=True)
+    A1 = MatrixSymbol('A1', n, n)
+    A2 = MatrixSymbol('A2', n, m)
+    A3 = MatrixSymbol('A3', n, k)
+    A4 = MatrixSymbol('A4', m, n)
+    A5 = MatrixSymbol('A5', m, m)
+    A6 = MatrixSymbol('A6', m, k)
+    A7 = MatrixSymbol('A7', k, n)
+    A8 = MatrixSymbol('A8', k, m)
+    A9 = MatrixSymbol('A9', k, k)
+    A = BlockMatrix([[A1, A2, A3], [A4, A5, A6], [A7, A8, A9]])
+    assert A[n + i, n + i] == MatrixElement(A, n + i, n + i)
+
+
+@XFAIL
+def test_block_index_symbolic_fail():
+    # To make this work, symbolic matrix dimensions would need to be somehow assumed nonnegative
+    # even if the symbols aren't specified as such.  Then 2 * n < n would correctly evaluate to
+    # False in BlockMatrix._entry()
+    A1 = MatrixSymbol('A1', n, 1)
+    A2 = MatrixSymbol('A2', m, 1)
+    A = BlockMatrix([[A1], [A2]])
+    assert A[2 * n, 0] == A2[n, 0]
+
+
 def test_slicing():
     A.as_explicit()[0, :]  # does not raise an error
 

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/matrices/expressions/tests/test_blockmatrix.py sympy/matrices/expressions/tests/test_indexing.py
: '>>>>> End Test Output'
git checkout f9e030b57623bebdc2efa7f297c1b5ede08fcebf sympy/matrices/expressions/tests/test_blockmatrix.py sympy/matrices/expressions/tests/test_indexing.py
