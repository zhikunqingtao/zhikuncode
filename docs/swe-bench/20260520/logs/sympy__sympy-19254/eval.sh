#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff e0ef1da13e2ab2a77866c05246f73c871ca9388c
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout e0ef1da13e2ab2a77866c05246f73c871ca9388c sympy/polys/tests/test_factortools.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/polys/tests/test_factortools.py b/sympy/polys/tests/test_factortools.py
--- a/sympy/polys/tests/test_factortools.py
+++ b/sympy/polys/tests/test_factortools.py
@@ -27,7 +27,8 @@ def test_dmp_trial_division():
 
 def test_dup_zz_mignotte_bound():
     R, x = ring("x", ZZ)
-    assert R.dup_zz_mignotte_bound(2*x**2 + 3*x + 4) == 32
+    assert R.dup_zz_mignotte_bound(2*x**2 + 3*x + 4) == 6
+    assert R.dup_zz_mignotte_bound(x**3 + 14*x**2 + 56*x + 64) == 152
 
 
 def test_dmp_zz_mignotte_bound():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/polys/tests/test_factortools.py
: '>>>>> End Test Output'
git checkout e0ef1da13e2ab2a77866c05246f73c871ca9388c sympy/polys/tests/test_factortools.py
