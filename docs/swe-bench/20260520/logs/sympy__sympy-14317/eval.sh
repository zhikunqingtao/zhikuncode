#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff fb536869fb7aa28b2695ad7a3b70949926b291c4
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout fb536869fb7aa28b2695ad7a3b70949926b291c4 sympy/printing/tests/test_latex.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_latex.py b/sympy/printing/tests/test_latex.py
--- a/sympy/printing/tests/test_latex.py
+++ b/sympy/printing/tests/test_latex.py
@@ -1132,11 +1132,20 @@ def test_latex_Poly():
     assert latex(Poly(x**2 + 2 * x, x)) == \
         r"\operatorname{Poly}{\left( x^{2} + 2 x, x, domain=\mathbb{Z} \right)}"
     assert latex(Poly(x/y, x)) == \
-        r"\operatorname{Poly}{\left( \frac{x}{y}, x, domain=\mathbb{Z}\left(y\right) \right)}"
+        r"\operatorname{Poly}{\left( \frac{1}{y} x, x, domain=\mathbb{Z}\left(y\right) \right)}"
     assert latex(Poly(2.0*x + y)) == \
         r"\operatorname{Poly}{\left( 2.0 x + 1.0 y, x, y, domain=\mathbb{R} \right)}"
 
 
+def test_latex_Poly_order():
+    assert latex(Poly([a, 1, b, 2, c, 3], x)) == \
+        '\\operatorname{Poly}{\\left( a x^{5} + x^{4} + b x^{3} + 2 x^{2} + c x + 3, x, domain=\\mathbb{Z}\\left[a, b, c\\right] \\right)}'
+    assert latex(Poly([a, 1, b+c, 2, 3], x)) == \
+        '\\operatorname{Poly}{\\left( a x^{4} + x^{3} + \\left(b + c\\right) x^{2} + 2 x + 3, x, domain=\\mathbb{Z}\\left[a, b, c\\right] \\right)}'
+    assert latex(Poly(a*x**3 + x**2*y - x*y - c*y**3 - b*x*y**2 + y - a*x + b, (x, y))) == \
+        '\\operatorname{Poly}{\\left( a x^{3} + x^{2}y -  b xy^{2} - xy -  a x -  c y^{3} + y + b, x, y, domain=\\mathbb{Z}\\left[a, b, c\\right] \\right)}'
+
+
 def test_latex_ComplexRootOf():
     assert latex(rootof(x**5 + x + 3, 0)) == \
         r"\operatorname{CRootOf} {\left(x^{5} + x + 3, 0\right)}"

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_latex.py
: '>>>>> End Test Output'
git checkout fb536869fb7aa28b2695ad7a3b70949926b291c4 sympy/printing/tests/test_latex.py
