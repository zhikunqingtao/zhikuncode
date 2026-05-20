#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff e727339af6dc22321b00f52d971cda39e4ce89fb
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout e727339af6dc22321b00f52d971cda39e4ce89fb sympy/sets/tests/test_sets.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/sets/tests/test_sets.py b/sympy/sets/tests/test_sets.py
--- a/sympy/sets/tests/test_sets.py
+++ b/sympy/sets/tests/test_sets.py
@@ -21,7 +21,7 @@ def test_imageset():
     assert imageset(x, abs(x), S.Integers) is S.Naturals0
     # issue 16878a
     r = symbols('r', real=True)
-    assert (1, r) not in imageset(x, (x, x), S.Reals)
+    assert (1, r) in imageset(x, (x, x), S.Reals) != False
     assert (r, r) in imageset(x, (x, x), S.Reals)
     assert 1 + I in imageset(x, x + I, S.Reals)
     assert {1} not in imageset(x, (x,), S.Reals)
@@ -342,6 +342,9 @@ def test_intersection():
     # issue 12178
     assert Intersection() == S.UniversalSet
 
+    # issue 16987
+    assert Intersection({1}, {1}, {x}) == Intersection({1}, {x})
+
 
 def test_issue_9623():
     n = Symbol('n')

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/sets/tests/test_sets.py
: '>>>>> End Test Output'
git checkout e727339af6dc22321b00f52d971cda39e4ce89fb sympy/sets/tests/test_sets.py
