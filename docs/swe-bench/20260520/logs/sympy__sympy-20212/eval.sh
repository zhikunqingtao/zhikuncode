#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff a106f4782a9dbe7f8fd16030f15401d977e03ae9
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout a106f4782a9dbe7f8fd16030f15401d977e03ae9 sympy/core/tests/test_power.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_power.py b/sympy/core/tests/test_power.py
--- a/sympy/core/tests/test_power.py
+++ b/sympy/core/tests/test_power.py
@@ -266,6 +266,9 @@ def test_zero():
     assert 0**(2*x*y) == 0**(x*y)
     assert 0**(-2*x*y) == S.ComplexInfinity**(x*y)
 
+    #Test issue 19572
+    assert 0 ** -oo is zoo
+    assert power(0, -oo) is zoo
 
 def test_pow_as_base_exp():
     x = Symbol('x')

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_power.py
: '>>>>> End Test Output'
git checkout a106f4782a9dbe7f8fd16030f15401d977e03ae9 sympy/core/tests/test_power.py
