#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 74227f900b05009d4eed62e34a166228788a32ca
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 74227f900b05009d4eed62e34a166228788a32ca sympy/codegen/tests/test_cnodes.py sympy/core/tests/test_basic.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/codegen/tests/test_cnodes.py b/sympy/codegen/tests/test_cnodes.py
--- a/sympy/codegen/tests/test_cnodes.py
+++ b/sympy/codegen/tests/test_cnodes.py
@@ -1,6 +1,6 @@
 from sympy.core.symbol import symbols
 from sympy.printing.ccode import ccode
-from sympy.codegen.ast import Declaration, Variable, float64, int64
+from sympy.codegen.ast import Declaration, Variable, float64, int64, String
 from sympy.codegen.cnodes import (
     alignof, CommaOperator, goto, Label, PreDecrement, PostDecrement, PreIncrement, PostIncrement,
     sizeof, union, struct
@@ -66,7 +66,7 @@ def test_sizeof():
     assert ccode(sz) == 'sizeof(%s)' % typename
     assert sz.func(*sz.args) == sz
     assert not sz.is_Atom
-    assert all(atom == typename for atom in sz.atoms())
+    assert sz.atoms() == {String('unsigned int'), String('sizeof')}
 
 
 def test_struct():
diff --git a/sympy/core/tests/test_basic.py b/sympy/core/tests/test_basic.py
--- a/sympy/core/tests/test_basic.py
+++ b/sympy/core/tests/test_basic.py
@@ -137,7 +137,7 @@ def test_subs_with_unicode_symbols():
 
 
 def test_atoms():
-    assert b21.atoms() == set()
+    assert b21.atoms() == set([Basic()])
 
 
 def test_free_symbols_empty():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/codegen/tests/test_cnodes.py sympy/core/tests/test_basic.py
: '>>>>> End Test Output'
git checkout 74227f900b05009d4eed62e34a166228788a32ca sympy/codegen/tests/test_cnodes.py sympy/core/tests/test_basic.py
