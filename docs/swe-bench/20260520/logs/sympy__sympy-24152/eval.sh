#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff b9af885473ad7e34b5b0826cb424dd26d8934670
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout b9af885473ad7e34b5b0826cb424dd26d8934670 sympy/physics/quantum/tests/test_tensorproduct.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/physics/quantum/tests/test_tensorproduct.py b/sympy/physics/quantum/tests/test_tensorproduct.py
--- a/sympy/physics/quantum/tests/test_tensorproduct.py
+++ b/sympy/physics/quantum/tests/test_tensorproduct.py
@@ -44,6 +44,13 @@ def test_tensor_product_abstract():
 def test_tensor_product_expand():
     assert TP(A + B, B + C).expand(tensorproduct=True) == \
         TP(A, B) + TP(A, C) + TP(B, B) + TP(B, C)
+    #Tests for fix of issue #24142
+    assert TP(A-B, B-A).expand(tensorproduct=True) == \
+        TP(A, B) - TP(A, A) - TP(B, B) + TP(B, A)
+    assert TP(2*A + B, A + B).expand(tensorproduct=True) == \
+        2 * TP(A, A) + 2 * TP(A, B) + TP(B, A) + TP(B, B)
+    assert TP(2 * A * B + A, A + B).expand(tensorproduct=True) == \
+        2 * TP(A*B, A) + 2 * TP(A*B, B) + TP(A, A) + TP(A, B)
 
 
 def test_tensor_product_commutator():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/physics/quantum/tests/test_tensorproduct.py
: '>>>>> End Test Output'
git checkout b9af885473ad7e34b5b0826cb424dd26d8934670 sympy/physics/quantum/tests/test_tensorproduct.py
