#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 3546ac7ed78e1780c1a76929864bb33330055740
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 3546ac7ed78e1780c1a76929864bb33330055740 sympy/core/tests/test_numbers.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_numbers.py b/sympy/core/tests/test_numbers.py
--- a/sympy/core/tests/test_numbers.py
+++ b/sympy/core/tests/test_numbers.py
@@ -582,6 +582,12 @@ def test_Float_issue_2107():
     assert S.Zero + b + (-b) == 0
 
 
+def test_Float_from_tuple():
+    a = Float((0, '1L', 0, 1))
+    b = Float((0, '1', 0, 1))
+    assert a == b
+
+
 def test_Infinity():
     assert oo != 1
     assert 1*oo == oo

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_numbers.py
: '>>>>> End Test Output'
git checkout 3546ac7ed78e1780c1a76929864bb33330055740 sympy/core/tests/test_numbers.py
