#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff a3389a25ec84d36f5cf04a4f2562d820f131db64
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout a3389a25ec84d36f5cf04a4f2562d820f131db64 sympy/integrals/tests/test_intpoly.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/integrals/tests/test_intpoly.py b/sympy/integrals/tests/test_intpoly.py
--- a/sympy/integrals/tests/test_intpoly.py
+++ b/sympy/integrals/tests/test_intpoly.py
@@ -26,15 +26,15 @@ def test_decompose():
     assert decompose(9*x**2 + y + 4*x + x**3 + y**2*x + 3) ==\
         {0: 3, 1: 4*x + y, 2: 9*x**2, 3: x**3 + x*y**2}
 
-    assert decompose(x, True) == [x]
-    assert decompose(x ** 2, True) == [x ** 2]
-    assert decompose(x * y, True) == [x * y]
-    assert decompose(x + y, True) == [x, y]
-    assert decompose(x ** 2 + y, True) == [y, x ** 2]
-    assert decompose(8 * x ** 2 + 4 * y + 7, True) == [7, 4*y, 8*x**2]
-    assert decompose(x ** 2 + 3 * y * x, True) == [x ** 2, 3 * x * y]
+    assert decompose(x, True) == {x}
+    assert decompose(x ** 2, True) == {x**2}
+    assert decompose(x * y, True) == {x * y}
+    assert decompose(x + y, True) == {x, y}
+    assert decompose(x ** 2 + y, True) == {y, x ** 2}
+    assert decompose(8 * x ** 2 + 4 * y + 7, True) == {7, 4*y, 8*x**2}
+    assert decompose(x ** 2 + 3 * y * x, True) == {x ** 2, 3 * x * y}
     assert decompose(9 * x ** 2 + y + 4 * x + x ** 3 + y ** 2 * x + 3, True) == \
-           [3, y, x**3, 4*x, 9*x**2, x*y**2]
+           {3, y, 4*x, 9*x**2, x*y**2, x**3}
 
 
 def test_best_origin():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/integrals/tests/test_intpoly.py
: '>>>>> End Test Output'
git checkout a3389a25ec84d36f5cf04a4f2562d820f131db64 sympy/integrals/tests/test_intpoly.py
