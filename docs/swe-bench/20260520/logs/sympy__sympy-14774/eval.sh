#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 8fc63c2d71752389a44367b8ef4aba8a91af6a45
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 8fc63c2d71752389a44367b8ef4aba8a91af6a45 sympy/printing/tests/test_latex.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_latex.py b/sympy/printing/tests/test_latex.py
--- a/sympy/printing/tests/test_latex.py
+++ b/sympy/printing/tests/test_latex.py
@@ -6,7 +6,7 @@
     Lambda, LaplaceTransform, Limit, Matrix, Max, MellinTransform, Min, Mul,
     Order, Piecewise, Poly, ring, field, ZZ, Pow, Product, Range, Rational,
     RisingFactorial, rootof, RootSum, S, Shi, Si, SineTransform, Subs,
-    Sum, Symbol, ImageSet, Tuple, Union, Ynm, Znm, arg, asin, Mod,
+    Sum, Symbol, ImageSet, Tuple, Union, Ynm, Znm, arg, asin, acsc, Mod,
     assoc_laguerre, assoc_legendre, beta, binomial, catalan, ceiling, Complement,
     chebyshevt, chebyshevu, conjugate, cot, coth, diff, dirichlet_eta, euler,
     exp, expint, factorial, factorial2, floor, gamma, gegenbauer, hermite,
@@ -305,6 +305,8 @@ def test_latex_functions():
     assert latex(asin(x**2), inv_trig_style="power",
                  fold_func_brackets=True) == \
         r"\sin^{-1} {x^{2}}"
+    assert latex(acsc(x), inv_trig_style="full") == \
+        r"\operatorname{arccsc}{\left (x \right )}"
 
     assert latex(factorial(k)) == r"k!"
     assert latex(factorial(-k)) == r"\left(- k\right)!"

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_latex.py
: '>>>>> End Test Output'
git checkout 8fc63c2d71752389a44367b8ef4aba8a91af6a45 sympy/printing/tests/test_latex.py
