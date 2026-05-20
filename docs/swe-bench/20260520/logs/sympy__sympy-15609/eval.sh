#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 15f56f3b0006d2ed2c29bde3c43e91618012c849
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 15f56f3b0006d2ed2c29bde3c43e91618012c849 sympy/printing/tests/test_latex.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_latex.py b/sympy/printing/tests/test_latex.py
--- a/sympy/printing/tests/test_latex.py
+++ b/sympy/printing/tests/test_latex.py
@@ -1738,6 +1738,11 @@ def test_MatrixElement_printing():
     F = C[0, 0].subs(C, A - B)
     assert latex(F) == r"\left(A - B\right)_{0, 0}"
 
+    i, j, k = symbols("i j k")
+    M = MatrixSymbol("M", k, k)
+    N = MatrixSymbol("N", k, k)
+    assert latex((M*N)[i, j]) == r'\sum_{i_{1}=0}^{k - 1} M_{i, i_{1}} N_{i_{1}, j}'
+
 
 def test_MatrixSymbol_printing():
     # test cases for issue #14237

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_latex.py
: '>>>>> End Test Output'
git checkout 15f56f3b0006d2ed2c29bde3c43e91618012c849 sympy/printing/tests/test_latex.py
