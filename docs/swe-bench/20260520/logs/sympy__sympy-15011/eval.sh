#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff b7c5ba2bf3ffd5cf453b25af7c8ddd9a639800cb
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout b7c5ba2bf3ffd5cf453b25af7c8ddd9a639800cb sympy/utilities/tests/test_lambdify.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/utilities/tests/test_lambdify.py b/sympy/utilities/tests/test_lambdify.py
--- a/sympy/utilities/tests/test_lambdify.py
+++ b/sympy/utilities/tests/test_lambdify.py
@@ -728,6 +728,14 @@ def test_dummification():
     raises(SyntaxError, lambda: lambdify(2 * F(t), 2 * F(t) + 5))
     raises(SyntaxError, lambda: lambdify(2 * F(t), 4 * F(t) + 5))
 
+def test_curly_matrix_symbol():
+    # Issue #15009
+    curlyv = sympy.MatrixSymbol("{v}", 2, 1)
+    lam = lambdify(curlyv, curlyv)
+    assert lam(1)==1
+    lam = lambdify(curlyv, curlyv, dummify=True)
+    assert lam(1)==1
+
 def test_python_keywords():
     # Test for issue 7452. The automatic dummification should ensure use of
     # Python reserved keywords as symbol names will create valid lambda

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/utilities/tests/test_lambdify.py
: '>>>>> End Test Output'
git checkout b7c5ba2bf3ffd5cf453b25af7c8ddd9a639800cb sympy/utilities/tests/test_lambdify.py
