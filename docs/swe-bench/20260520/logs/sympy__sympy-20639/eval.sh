#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff eb926a1d0c1158bf43f01eaf673dc84416b5ebb1
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout eb926a1d0c1158bf43f01eaf673dc84416b5ebb1 sympy/printing/pretty/tests/test_pretty.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/printing/pretty/tests/test_pretty.py b/sympy/printing/pretty/tests/test_pretty.py
--- a/sympy/printing/pretty/tests/test_pretty.py
+++ b/sympy/printing/pretty/tests/test_pretty.py
@@ -5942,7 +5942,11 @@ def test_PrettyPoly():
 
 def test_issue_6285():
     assert pretty(Pow(2, -5, evaluate=False)) == '1 \n--\n 5\n2 '
-    assert pretty(Pow(x, (1/pi))) == 'pi___\n\\/ x '
+    assert pretty(Pow(x, (1/pi))) == \
+    ' 1 \n'\
+    ' --\n'\
+    ' pi\n'\
+    'x  '
 
 
 def test_issue_6359():
@@ -7205,6 +7209,51 @@ def test_is_combining():
         [False, True, False, False]
 
 
+def test_issue_17616():
+    assert pretty(pi**(1/exp(1))) == \
+   '  / -1\\\n'\
+   '  \e  /\n'\
+   'pi     '
+
+    assert upretty(pi**(1/exp(1))) == \
+   ' ⎛ -1⎞\n'\
+   ' ⎝ℯ  ⎠\n'\
+   'π     '
+
+    assert pretty(pi**(1/pi)) == \
+    '  1 \n'\
+    '  --\n'\
+    '  pi\n'\
+    'pi  '
+
+    assert upretty(pi**(1/pi)) == \
+    ' 1\n'\
+    ' ─\n'\
+    ' π\n'\
+    'π '
+
+    assert pretty(pi**(1/EulerGamma)) == \
+    '      1     \n'\
+    '  ----------\n'\
+    '  EulerGamma\n'\
+    'pi          '
+
+    assert upretty(pi**(1/EulerGamma)) == \
+    ' 1\n'\
+    ' ─\n'\
+    ' γ\n'\
+    'π '
+
+    z = Symbol("x_17")
+    assert upretty(7**(1/z)) == \
+    'x₁₇___\n'\
+    ' ╲╱ 7 '
+
+    assert pretty(7**(1/z)) == \
+    'x_17___\n'\
+    '  \\/ 7 '
+
+
 def test_issue_17857():
     assert pretty(Range(-oo, oo)) == '{..., -1, 0, 1, ...}'
     assert pretty(Range(oo, -oo, -1)) == '{..., 1, 0, -1, ...}'

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/printing/pretty/tests/test_pretty.py
: '>>>>> End Test Output'
git checkout eb926a1d0c1158bf43f01eaf673dc84416b5ebb1 sympy/printing/pretty/tests/test_pretty.py
