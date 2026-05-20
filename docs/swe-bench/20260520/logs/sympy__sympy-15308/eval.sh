#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff fb59d703e6863ed803c98177b59197b5513332e9
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout fb59d703e6863ed803c98177b59197b5513332e9 sympy/printing/tests/test_latex.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_latex.py b/sympy/printing/tests/test_latex.py
--- a/sympy/printing/tests/test_latex.py
+++ b/sympy/printing/tests/test_latex.py
@@ -1866,3 +1866,35 @@ def test_latex_printer_tensor():
 
     expr = TensorElement(K(i,j,-k,-l), {i:3})
     assert latex(expr) == 'K{}^{i=3,j}{}_{kl}'
+
+
+def test_trace():
+    # Issue 15303
+    from sympy import trace
+    A = MatrixSymbol("A", 2, 2)
+    assert latex(trace(A)) == r"\mathrm{tr}\left (A \right )"
+    assert latex(trace(A**2)) == r"\mathrm{tr}\left (A^{2} \right )"
+
+
+def test_print_basic():
+    # Issue 15303
+    from sympy import Basic, Expr
+
+    # dummy class for testing printing where the function is not implemented in latex.py
+    class UnimplementedExpr(Expr):
+        def __new__(cls, e):
+            return Basic.__new__(cls, e)
+
+    # dummy function for testing
+    def unimplemented_expr(expr):
+        return UnimplementedExpr(expr).doit()
+
+    # override class name to use superscript / subscript
+    def unimplemented_expr_sup_sub(expr):
+        result = UnimplementedExpr(expr)
+        result.__class__.__name__ = 'UnimplementedExpr_x^1'
+        return result
+
+    assert latex(unimplemented_expr(x)) == r'UnimplementedExpr\left(x\right)'
+    assert latex(unimplemented_expr(x**2)) == r'UnimplementedExpr\left(x^{2}\right)'
+    assert latex(unimplemented_expr_sup_sub(x)) == r'UnimplementedExpr^{1}_{x}\left(x\right)'

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_latex.py
: '>>>>> End Test Output'
git checkout fb59d703e6863ed803c98177b59197b5513332e9 sympy/printing/tests/test_latex.py
