#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 516fa83e69caf1e68306cfc912a13f36c434d51c
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 516fa83e69caf1e68306cfc912a13f36c434d51c sympy/utilities/tests/test_iterables.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/utilities/tests/test_iterables.py b/sympy/utilities/tests/test_iterables.py
--- a/sympy/utilities/tests/test_iterables.py
+++ b/sympy/utilities/tests/test_iterables.py
@@ -703,6 +703,10 @@ def test_uniq():
         [([1], 2, 2), (2, [1], 2), (2, 2, [1])]
     assert list(uniq([2, 3, 2, 4, [2], [1], [2], [3], [1]])) == \
         [2, 3, 4, [2], [1], [3]]
+    f = [1]
+    raises(RuntimeError, lambda: [f.remove(i) for i in uniq(f)])
+    f = [[1]]
+    raises(RuntimeError, lambda: [f.remove(i) for i in uniq(f)])
 
 
 def test_kbins():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/utilities/tests/test_iterables.py
: '>>>>> End Test Output'
git checkout 516fa83e69caf1e68306cfc912a13f36c434d51c sympy/utilities/tests/test_iterables.py
