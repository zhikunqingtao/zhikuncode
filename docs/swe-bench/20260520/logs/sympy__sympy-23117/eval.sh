#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff c5cef2499d6eed024b0db5c792d6ec7c53baa470
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout c5cef2499d6eed024b0db5c792d6ec7c53baa470 sympy/tensor/array/tests/test_ndim_array.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/tensor/array/tests/test_ndim_array.py b/sympy/tensor/array/tests/test_ndim_array.py
--- a/sympy/tensor/array/tests/test_ndim_array.py
+++ b/sympy/tensor/array/tests/test_ndim_array.py
@@ -10,6 +10,11 @@
 
 from sympy.abc import x, y
 
+mutable_array_types = [
+    MutableDenseNDimArray,
+    MutableSparseNDimArray
+]
+
 array_types = [
     ImmutableDenseNDimArray,
     ImmutableSparseNDimArray,
@@ -46,7 +51,23 @@ def test_issue_18361():
     assert simplify(B) == Array([1, 0])
     assert simplify(C) == Array([x + 1, sin(2*x)])
 
+
 def test_issue_20222():
     A = Array([[1, 2], [3, 4]])
     B = Matrix([[1,2],[3,4]])
     raises(TypeError, lambda: A - B)
+
+
+def test_issue_17851():
+    for array_type in array_types:
+        A = array_type([])
+        assert isinstance(A, array_type)
+        assert A.shape == (0,)
+        assert list(A) == []
+
+
+def test_issue_and_18715():
+    for array_type in mutable_array_types:
+        A = array_type([0, 1, 2])
+        A[0] += 5
+        assert A[0] == 5

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/tensor/array/tests/test_ndim_array.py
: '>>>>> End Test Output'
git checkout c5cef2499d6eed024b0db5c792d6ec7c53baa470 sympy/tensor/array/tests/test_ndim_array.py
