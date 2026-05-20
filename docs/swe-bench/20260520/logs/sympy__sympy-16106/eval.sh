#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 0e987498b00167fdd4a08a41c852a97cb70ce8f2
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 0e987498b00167fdd4a08a41c852a97cb70ce8f2 sympy/printing/tests/test_mathml.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_mathml.py b/sympy/printing/tests/test_mathml.py
--- a/sympy/printing/tests/test_mathml.py
+++ b/sympy/printing/tests/test_mathml.py
@@ -1,7 +1,7 @@
 from sympy import diff, Integral, Limit, sin, Symbol, Integer, Rational, cos, \
     tan, asin, acos, atan, sinh, cosh, tanh, asinh, acosh, atanh, E, I, oo, \
     pi, GoldenRatio, EulerGamma, Sum, Eq, Ne, Ge, Lt, Float, Matrix, Basic, S, \
-    MatrixSymbol, Function, Derivative, log, Lambda
+    MatrixSymbol, Function, Derivative, log, Lambda, IndexedBase, symbols
 from sympy.core.containers import Tuple
 from sympy.functions.elementary.complexes import re, im, Abs, conjugate
 from sympy.functions.elementary.integers import floor, ceiling
@@ -1139,3 +1139,17 @@ def test_print_random_symbol():
     R = RandomSymbol(Symbol('R'))
     assert mpp.doprint(R) == '<mi>R</mi>'
     assert mp.doprint(R) == '<ci>R</ci>'
+
+
+def test_print_IndexedBase():
+    a,b,c,d,e = symbols('a b c d e')
+    assert mathml(IndexedBase(a)[b],printer='presentation') == '<msub><mi>a</mi><mi>b</mi></msub>'
+    assert mathml(IndexedBase(a)[b,c,d],printer = 'presentation') == '<msub><mi>a</mi><mfenced><mi>b</mi><mi>c</mi><mi>d</mi></mfenced></msub>'
+    assert mathml(IndexedBase(a)[b]*IndexedBase(c)[d]*IndexedBase(e),printer = 'presentation') == '<mrow><msub><mi>a</mi><mi>b</mi></msub><mo>&InvisibleTimes;</mo><msub><mi>c</mi><mi>d</mi></msub><mo>&InvisibleTimes;</mo><mi>e</mi></mrow>'
+
+
+def test_print_Indexed():
+    a,b,c = symbols('a b c')
+    assert mathml(IndexedBase(a),printer = 'presentation') == '<mi>a</mi>'
+    assert mathml(IndexedBase(a/b),printer = 'presentation') == '<mrow><mfrac><mi>a</mi><mi>b</mi></mfrac></mrow>'
+    assert mathml(IndexedBase((a,b)),printer = 'presentation') == '<mrow><mfenced><mi>a</mi><mi>b</mi></mfenced></mrow>'

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_mathml.py
: '>>>>> End Test Output'
git checkout 0e987498b00167fdd4a08a41c852a97cb70ce8f2 sympy/printing/tests/test_mathml.py
