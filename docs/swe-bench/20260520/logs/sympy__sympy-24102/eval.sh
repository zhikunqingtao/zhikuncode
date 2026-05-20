#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 58598660a3f6ab3d918781c4988c2e4b2bdd9297
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 58598660a3f6ab3d918781c4988c2e4b2bdd9297 sympy/parsing/tests/test_mathematica.py sympy/testing/quality_unicode.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/parsing/tests/test_mathematica.py b/sympy/parsing/tests/test_mathematica.py
--- a/sympy/parsing/tests/test_mathematica.py
+++ b/sympy/parsing/tests/test_mathematica.py
@@ -15,6 +15,7 @@ def test_mathematica():
         'x+y': 'x+y',
         '355/113': '355/113',
         '2.718281828': '2.718281828',
+        'Cos(1/2 * π)': 'Cos(π/2)',
         'Sin[12]': 'sin(12)',
         'Exp[Log[4]]': 'exp(log(4))',
         '(x+1)(x+3)': '(x+1)*(x+3)',
@@ -94,6 +95,7 @@ def test_parser_mathematica_tokenizer():
     assert chain("+x") == "x"
     assert chain("-1") == "-1"
     assert chain("- 3") == "-3"
+    assert chain("α") == "α"
     assert chain("+Sin[x]") == ["Sin", "x"]
     assert chain("-Sin[x]") == ["Times", "-1", ["Sin", "x"]]
     assert chain("x(a+1)") == ["Times", "x", ["Plus", "a", "1"]]
diff --git a/sympy/testing/quality_unicode.py b/sympy/testing/quality_unicode.py
--- a/sympy/testing/quality_unicode.py
+++ b/sympy/testing/quality_unicode.py
@@ -48,6 +48,8 @@
 
 unicode_strict_whitelist = [
     r'*/sympy/parsing/latex/_antlr/__init__.py',
+    # test_mathematica.py uses some unicode for testing Greek characters are working #24055
+    r'*/sympy/parsing/tests/test_mathematica.py',
 ]
 
 

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/parsing/tests/test_mathematica.py sympy/testing/quality_unicode.py
: '>>>>> End Test Output'
git checkout 58598660a3f6ab3d918781c4988c2e4b2bdd9297 sympy/parsing/tests/test_mathematica.py sympy/testing/quality_unicode.py
