#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 7121bdf1facdd90d05b6994b4c2e5b2865a4638a
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 7121bdf1facdd90d05b6994b4c2e5b2865a4638a sympy/matrices/tests/test_commonmatrix.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/matrices/tests/test_commonmatrix.py b/sympy/matrices/tests/test_commonmatrix.py
--- a/sympy/matrices/tests/test_commonmatrix.py
+++ b/sympy/matrices/tests/test_commonmatrix.py
@@ -674,6 +674,30 @@ def test_multiplication():
         assert c[1, 0] == 3*5
         assert c[1, 1] == 0
 
+def test_matmul():
+    a = Matrix([[1, 2], [3, 4]])
+
+    assert a.__matmul__(2) == NotImplemented
+
+    assert a.__rmatmul__(2) == NotImplemented
+
+    #This is done this way because @ is only supported in Python 3.5+
+    #To check 2@a case
+    try:
+        eval('2 @ a')
+    except SyntaxError:
+        pass
+    except TypeError:  #TypeError is raised in case of NotImplemented is returned
+        pass
+
+    #Check a@2 case
+    try:
+        eval('a @ 2')
+    except SyntaxError:
+        pass
+    except TypeError:  #TypeError is raised in case of NotImplemented is returned
+        pass
+
 def test_power():
     raises(NonSquareMatrixError, lambda: Matrix((1, 2))**2)
 

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/matrices/tests/test_commonmatrix.py
: '>>>>> End Test Output'
git checkout 7121bdf1facdd90d05b6994b4c2e5b2865a4638a sympy/matrices/tests/test_commonmatrix.py
