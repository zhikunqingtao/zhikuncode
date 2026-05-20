#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff d822fcba181155b85ff2b29fe525adbafb22b448
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout d822fcba181155b85ff2b29fe525adbafb22b448 sympy/simplify/tests/test_cse.py sympy/utilities/tests/test_codegen.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/simplify/tests/test_cse.py b/sympy/simplify/tests/test_cse.py
--- a/sympy/simplify/tests/test_cse.py
+++ b/sympy/simplify/tests/test_cse.py
@@ -347,6 +347,10 @@ def test_cse_MatrixSymbol():
     B = MatrixSymbol("B", n, n)
     assert cse(B) == ([], [B])
 
+    assert cse(A[0] * A[0]) == ([], [A[0]*A[0]])
+
+    assert cse(A[0,0]*A[0,1] + A[0,0]*A[0,1]*A[0,2]) == ([(x0, A[0, 0]*A[0, 1])], [x0*A[0, 2] + x0])
+
 def test_cse_MatrixExpr():
     A = MatrixSymbol('A', 3, 3)
     y = MatrixSymbol('y', 3, 1)
diff --git a/sympy/utilities/tests/test_codegen.py b/sympy/utilities/tests/test_codegen.py
--- a/sympy/utilities/tests/test_codegen.py
+++ b/sympy/utilities/tests/test_codegen.py
@@ -531,26 +531,9 @@ def test_multidim_c_argument_cse():
         '#include "test.h"\n'
         "#include <math.h>\n"
         "void c(double *A, double *b, double *out) {\n"
-        "   double x0[9];\n"
-        "   x0[0] = A[0];\n"
-        "   x0[1] = A[1];\n"
-        "   x0[2] = A[2];\n"
-        "   x0[3] = A[3];\n"
-        "   x0[4] = A[4];\n"
-        "   x0[5] = A[5];\n"
-        "   x0[6] = A[6];\n"
-        "   x0[7] = A[7];\n"
-        "   x0[8] = A[8];\n"
-        "   double x1[3];\n"
-        "   x1[0] = b[0];\n"
-        "   x1[1] = b[1];\n"
-        "   x1[2] = b[2];\n"
-        "   const double x2 = x1[0];\n"
-        "   const double x3 = x1[1];\n"
-        "   const double x4 = x1[2];\n"
-        "   out[0] = x2*x0[0] + x3*x0[1] + x4*x0[2];\n"
-        "   out[1] = x2*x0[3] + x3*x0[4] + x4*x0[5];\n"
-        "   out[2] = x2*x0[6] + x3*x0[7] + x4*x0[8];\n"
+        "   out[0] = A[0]*b[0] + A[1]*b[1] + A[2]*b[2];\n"
+        "   out[1] = A[3]*b[0] + A[4]*b[1] + A[5]*b[2];\n"
+        "   out[2] = A[6]*b[0] + A[7]*b[1] + A[8]*b[2];\n"
         "}\n"
     )
     assert code == expected

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/simplify/tests/test_cse.py sympy/utilities/tests/test_codegen.py
: '>>>>> End Test Output'
git checkout d822fcba181155b85ff2b29fe525adbafb22b448 sympy/simplify/tests/test_cse.py sympy/utilities/tests/test_codegen.py
