#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff f35ad6411f86a15dd78db39c29d1e5291f66f9b5
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout f35ad6411f86a15dd78db39c29d1e5291f66f9b5 sympy/polys/tests/test_polyoptions.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/polys/tests/test_polyoptions.py b/sympy/polys/tests/test_polyoptions.py
--- a/sympy/polys/tests/test_polyoptions.py
+++ b/sympy/polys/tests/test_polyoptions.py
@@ -6,7 +6,7 @@
     Frac, Formal, Polys, Include, All, Gen, Symbols, Method)
 
 from sympy.polys.orderings import lex
-from sympy.polys.domains import FF, GF, ZZ, QQ, EX
+from sympy.polys.domains import FF, GF, ZZ, QQ, RR, CC, EX
 
 from sympy.polys.polyerrors import OptionError, GeneratorsError
 
@@ -176,15 +176,23 @@ def test_Domain_preprocess():
 
     assert Domain.preprocess('Z[x]') == ZZ[x]
     assert Domain.preprocess('Q[x]') == QQ[x]
+    assert Domain.preprocess('R[x]') == RR[x]
+    assert Domain.preprocess('C[x]') == CC[x]
 
     assert Domain.preprocess('ZZ[x]') == ZZ[x]
     assert Domain.preprocess('QQ[x]') == QQ[x]
+    assert Domain.preprocess('RR[x]') == RR[x]
+    assert Domain.preprocess('CC[x]') == CC[x]
 
     assert Domain.preprocess('Z[x,y]') == ZZ[x, y]
     assert Domain.preprocess('Q[x,y]') == QQ[x, y]
+    assert Domain.preprocess('R[x,y]') == RR[x, y]
+    assert Domain.preprocess('C[x,y]') == CC[x, y]
 
     assert Domain.preprocess('ZZ[x,y]') == ZZ[x, y]
     assert Domain.preprocess('QQ[x,y]') == QQ[x, y]
+    assert Domain.preprocess('RR[x,y]') == RR[x, y]
+    assert Domain.preprocess('CC[x,y]') == CC[x, y]
 
     raises(OptionError, lambda: Domain.preprocess('Z()'))
 

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/polys/tests/test_polyoptions.py
: '>>>>> End Test Output'
git checkout f35ad6411f86a15dd78db39c29d1e5291f66f9b5 sympy/polys/tests/test_polyoptions.py
