#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff aa22709cb7df2d7503803d4b2c0baa7aa21440b6
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout aa22709cb7df2d7503803d4b2c0baa7aa21440b6 sympy/printing/tests/test_latex.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/tests/test_latex.py b/sympy/printing/tests/test_latex.py
--- a/sympy/printing/tests/test_latex.py
+++ b/sympy/printing/tests/test_latex.py
@@ -214,6 +214,19 @@ def test_latex_SingularityFunction():
     assert latex(SingularityFunction(x, 4, -1)) == \
         r"{\left\langle x - 4 \right\rangle}^{-1}"
 
+    assert latex(SingularityFunction(x, 4, 5)**3) == \
+        r"{\left({\langle x - 4 \rangle}^{5}\right)}^{3}"
+    assert latex(SingularityFunction(x, -3, 4)**3) == \
+        r"{\left({\langle x + 3 \rangle}^{4}\right)}^{3}"
+    assert latex(SingularityFunction(x, 0, 4)**3) == \
+        r"{\left({\langle x \rangle}^{4}\right)}^{3}"
+    assert latex(SingularityFunction(x, a, n)**3) == \
+        r"{\left({\langle - a + x \rangle}^{n}\right)}^{3}"
+    assert latex(SingularityFunction(x, 4, -2)**3) == \
+        r"{\left({\langle x - 4 \rangle}^{-2}\right)}^{3}"
+    assert latex((SingularityFunction(x, 4, -1)**3)**3) == \
+        r"{\left({\langle x - 4 \rangle}^{-1}\right)}^{9}"
+
 
 def test_latex_cycle():
     assert latex(Cycle(1, 2, 4)) == r"\left( 1\; 2\; 4\right)"

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/tests/test_latex.py
: '>>>>> End Test Output'
git checkout aa22709cb7df2d7503803d4b2c0baa7aa21440b6 sympy/printing/tests/test_latex.py
