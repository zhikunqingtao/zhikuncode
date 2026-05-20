#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 84c125972ad535b2dfb245f8d311d347b45e5b8a
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 84c125972ad535b2dfb245f8d311d347b45e5b8a sympy/printing/tests/test_latex.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_latex.py b/sympy/printing/tests/test_latex.py
--- a/sympy/printing/tests/test_latex.py
+++ b/sympy/printing/tests/test_latex.py
@@ -614,46 +614,46 @@ def test_latex_sequences():
     s1 = SeqFormula(a**2, (0, oo))
     s2 = SeqPer((1, 2))
 
-    latex_str = r'\left\[0, 1, 4, 9, \ldots\right\]'
+    latex_str = r'\left[0, 1, 4, 9, \ldots\right]'
     assert latex(s1) == latex_str
 
-    latex_str = r'\left\[1, 2, 1, 2, \ldots\right\]'
+    latex_str = r'\left[1, 2, 1, 2, \ldots\right]'
     assert latex(s2) == latex_str
 
     s3 = SeqFormula(a**2, (0, 2))
     s4 = SeqPer((1, 2), (0, 2))
 
-    latex_str = r'\left\[0, 1, 4\right\]'
+    latex_str = r'\left[0, 1, 4\right]'
     assert latex(s3) == latex_str
 
-    latex_str = r'\left\[1, 2, 1\right\]'
+    latex_str = r'\left[1, 2, 1\right]'
     assert latex(s4) == latex_str
 
     s5 = SeqFormula(a**2, (-oo, 0))
     s6 = SeqPer((1, 2), (-oo, 0))
 
-    latex_str = r'\left\[\ldots, 9, 4, 1, 0\right\]'
+    latex_str = r'\left[\ldots, 9, 4, 1, 0\right]'
     assert latex(s5) == latex_str
 
-    latex_str = r'\left\[\ldots, 2, 1, 2, 1\right\]'
+    latex_str = r'\left[\ldots, 2, 1, 2, 1\right]'
     assert latex(s6) == latex_str
 
-    latex_str = r'\left\[1, 3, 5, 11, \ldots\right\]'
+    latex_str = r'\left[1, 3, 5, 11, \ldots\right]'
     assert latex(SeqAdd(s1, s2)) == latex_str
 
-    latex_str = r'\left\[1, 3, 5\right\]'
+    latex_str = r'\left[1, 3, 5\right]'
     assert latex(SeqAdd(s3, s4)) == latex_str
 
-    latex_str = r'\left\[\ldots, 11, 5, 3, 1\right\]'
+    latex_str = r'\left[\ldots, 11, 5, 3, 1\right]'
     assert latex(SeqAdd(s5, s6)) == latex_str
 
-    latex_str = r'\left\[0, 2, 4, 18, \ldots\right\]'
+    latex_str = r'\left[0, 2, 4, 18, \ldots\right]'
     assert latex(SeqMul(s1, s2)) == latex_str
 
-    latex_str = r'\left\[0, 2, 4\right\]'
+    latex_str = r'\left[0, 2, 4\right]'
     assert latex(SeqMul(s3, s4)) == latex_str
 
-    latex_str = r'\left\[\ldots, 18, 4, 2, 0\right\]'
+    latex_str = r'\left[\ldots, 18, 4, 2, 0\right]'
     assert latex(SeqMul(s5, s6)) == latex_str
 
 

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_latex.py
: '>>>>> End Test Output'
git checkout 84c125972ad535b2dfb245f8d311d347b45e5b8a sympy/printing/tests/test_latex.py
