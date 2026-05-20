#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 0dbdc0ea83d339936da175f8c3a97d0d6bafb9f8
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 0dbdc0ea83d339936da175f8c3a97d0d6bafb9f8 sympy/printing/pretty/tests/test_pretty.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/pretty/tests/test_pretty.py b/sympy/printing/pretty/tests/test_pretty.py
--- a/sympy/printing/pretty/tests/test_pretty.py
+++ b/sympy/printing/pretty/tests/test_pretty.py
@@ -6094,11 +6094,16 @@ def test_MatrixSymbol_printing():
     A = MatrixSymbol("A", 3, 3)
     B = MatrixSymbol("B", 3, 3)
     C = MatrixSymbol("C", 3, 3)
-
     assert pretty(-A*B*C) == "-A*B*C"
     assert pretty(A - B) == "-B + A"
     assert pretty(A*B*C - A*B - B*C) == "-A*B -B*C + A*B*C"
 
+    # issue #14814
+    x = MatrixSymbol('x', n, n)
+    y = MatrixSymbol('y*', n, n)
+    assert pretty(x + y) == "x + y*"
+    assert pretty(-a*x + -2*y*y) == "-a*x -2*y**y*"
+
 
 def test_degree_printing():
     expr1 = 90*degree

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/pretty/tests/test_pretty.py
: '>>>>> End Test Output'
git checkout 0dbdc0ea83d339936da175f8c3a97d0d6bafb9f8 sympy/printing/pretty/tests/test_pretty.py
