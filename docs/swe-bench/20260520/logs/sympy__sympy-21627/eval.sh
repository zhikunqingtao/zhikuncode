#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 126f80578140e752ad5135aac77b8ff887eede3e
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 126f80578140e752ad5135aac77b8ff887eede3e sympy/functions/elementary/tests/test_complexes.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/functions/elementary/tests/test_complexes.py b/sympy/functions/elementary/tests/test_complexes.py
--- a/sympy/functions/elementary/tests/test_complexes.py
+++ b/sympy/functions/elementary/tests/test_complexes.py
@@ -464,6 +464,8 @@ def test_Abs():
     # issue 19627
     f = Function('f', positive=True)
     assert sqrt(f(x)**2) == f(x)
+    # issue 21625
+    assert unchanged(Abs, S("im(acos(-i + acosh(-g + i)))"))
 
 
 def test_Abs_rewrite():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/functions/elementary/tests/test_complexes.py
: '>>>>> End Test Output'
git checkout 126f80578140e752ad5135aac77b8ff887eede3e sympy/functions/elementary/tests/test_complexes.py
