#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff d3fcdb72bfcbb560eb45264ac1c03f359436edef
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout d3fcdb72bfcbb560eb45264ac1c03f359436edef sympy/matrices/tests/test_matrices.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/matrices/tests/test_matrices.py b/sympy/matrices/tests/test_matrices.py
--- a/sympy/matrices/tests/test_matrices.py
+++ b/sympy/matrices/tests/test_matrices.py
@@ -1225,6 +1225,8 @@ def test_is_upper():
     assert a.is_upper is True
     a = Matrix([[1], [2], [3]])
     assert a.is_upper is False
+    a = zeros(4, 2)
+    assert a.is_upper is True
 
 
 def test_is_lower():
@@ -1880,6 +1882,9 @@ def test_hessenberg():
     A = Matrix([[3, 4, 1], [2, 4, 5], [3, 1, 2]])
     assert not A.is_upper_hessenberg
 
+    A = zeros(5, 2)
+    assert A.is_upper_hessenberg
+
 
 def test_cholesky():
     raises(NonSquareMatrixError, lambda: Matrix((1, 2)).cholesky())

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/matrices/tests/test_matrices.py
: '>>>>> End Test Output'
git checkout d3fcdb72bfcbb560eb45264ac1c03f359436edef sympy/matrices/tests/test_matrices.py
