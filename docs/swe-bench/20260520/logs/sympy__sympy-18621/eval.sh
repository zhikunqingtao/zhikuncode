#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff b17ef6effe278d5b861d65896cc53442a6370d8f
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout b17ef6effe278d5b861d65896cc53442a6370d8f sympy/matrices/expressions/tests/test_blockmatrix.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/matrices/expressions/tests/test_blockmatrix.py b/sympy/matrices/expressions/tests/test_blockmatrix.py
--- a/sympy/matrices/expressions/tests/test_blockmatrix.py
+++ b/sympy/matrices/expressions/tests/test_blockmatrix.py
@@ -110,6 +110,10 @@ def test_issue_17624():
     assert block_collapse(b * b) == BlockMatrix([[a**2, z], [z, z]])
     assert block_collapse(b * b * b) == BlockMatrix([[a**3, z], [z, z]])
 
+def test_issue_18618():
+    A = Matrix([[1, 2, 3], [4, 5, 6], [7, 8, 9]])
+    assert A == Matrix(BlockDiagMatrix(A))
+
 def test_BlockMatrix_trace():
     A, B, C, D = [MatrixSymbol(s, 3, 3) for s in 'ABCD']
     X = BlockMatrix([[A, B], [C, D]])

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/matrices/expressions/tests/test_blockmatrix.py
: '>>>>> End Test Output'
git checkout b17ef6effe278d5b861d65896cc53442a6370d8f sympy/matrices/expressions/tests/test_blockmatrix.py
