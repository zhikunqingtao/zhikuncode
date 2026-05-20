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
git checkout fb536869fb7aa28b2695ad7a3b70949926b291c4 sympy/printing/pretty/tests/test_pretty.py sympy/vector/tests/test_printing.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/pretty/tests/test_pretty.py b/sympy/printing/pretty/tests/test_pretty.py
--- a/sympy/printing/pretty/tests/test_pretty.py
+++ b/sympy/printing/pretty/tests/test_pretty.py
@@ -6089,6 +6089,28 @@ def test_MatrixElement_printing():
     assert upretty(F) == ucode_str1
 
 
+def test_issue_12675():
+    from sympy.vector import CoordSys3D
+    x, y, t, j = symbols('x y t j')
+    e = CoordSys3D('e')
+
+    ucode_str = \
+u("""\
+⎛   t⎞    \n\
+⎜⎛x⎞ ⎟ e_j\n\
+⎜⎜─⎟ ⎟    \n\
+⎝⎝y⎠ ⎠    \
+""")
+    assert upretty((x/y)**t*e.j) == ucode_str
+    ucode_str = \
+u("""\
+⎛1⎞    \n\
+⎜─⎟ e_j\n\
+⎝y⎠    \
+""")
+    assert upretty((1/y)*e.j) == ucode_str
+
+
 def test_MatrixSymbol_printing():
     # test cases for issue #14237
     A = MatrixSymbol("A", 3, 3)
diff --git a/sympy/vector/tests/test_printing.py b/sympy/vector/tests/test_printing.py
--- a/sympy/vector/tests/test_printing.py
+++ b/sympy/vector/tests/test_printing.py
@@ -37,8 +37,8 @@ def upretty(expr):
 v.append(N.j - (Integral(f(b)) - C.x**2)*N.k)
 upretty_v_8 = u(
 """\
-N_j + ⎛   2   ⌠        ⎞ N_k\n\
-      ⎜C_x  - ⎮ f(b) db⎟    \n\
+      ⎛   2   ⌠        ⎞    \n\
+N_j + ⎜C_x  - ⎮ f(b) db⎟ N_k\n\
       ⎝       ⌡        ⎠    \
 """)
 pretty_v_8 = u(
@@ -55,9 +55,9 @@ def upretty(expr):
 v.append((a**2 + b)*N.i + (Integral(f(b)))*N.k)
 upretty_v_11 = u(
 """\
-⎛ 2    ⎞ N_i + ⎛⌠        ⎞ N_k\n\
-⎝a  + b⎠       ⎜⎮ f(b) db⎟    \n\
-               ⎝⌡        ⎠    \
+⎛ 2    ⎞        ⎛⌠        ⎞    \n\
+⎝a  + b⎠ N_i  + ⎜⎮ f(b) db⎟ N_k\n\
+                ⎝⌡        ⎠    \
 """)
 pretty_v_11 = u(
 """\
@@ -85,8 +85,8 @@ def upretty(expr):
 # This is the pretty form for ((a**2 + b)*N.i + 3*(C.y - c)*N.k) | N.k
 upretty_d_7 = u(
 """\
-⎛ 2    ⎞ (N_i|N_k) + (3⋅C_y - 3⋅c) (N_k|N_k)\n\
-⎝a  + b⎠                                    \
+⎛ 2    ⎞                                     \n\
+⎝a  + b⎠ (N_i|N_k)  + (3⋅C_y - 3⋅c) (N_k|N_k)\
 """)
 pretty_d_7 = u(
 """\

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/pretty/tests/test_pretty.py sympy/vector/tests/test_printing.py
: '>>>>> End Test Output'
git checkout fb536869fb7aa28b2695ad7a3b70949926b291c4 sympy/printing/pretty/tests/test_pretty.py sympy/vector/tests/test_printing.py
