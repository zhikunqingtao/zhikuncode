#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff d3b4158dea271485e3daa11bf82e69b8dab348ce
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout d3b4158dea271485e3daa11bf82e69b8dab348ce sympy/physics/units/tests/test_prefixes.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/physics/units/tests/test_prefixes.py b/sympy/physics/units/tests/test_prefixes.py
--- a/sympy/physics/units/tests/test_prefixes.py
+++ b/sympy/physics/units/tests/test_prefixes.py
@@ -2,7 +2,7 @@
 from sympy.core.numbers import Rational
 from sympy.core.singleton import S
 from sympy.core.symbol import (Symbol, symbols)
-from sympy.physics.units import Quantity, length, meter
+from sympy.physics.units import Quantity, length, meter, W
 from sympy.physics.units.prefixes import PREFIXES, Prefix, prefix_unit, kilo, \
     kibi
 from sympy.physics.units.systems import SI
@@ -17,7 +17,8 @@ def test_prefix_operations():
 
     dodeca = Prefix('dodeca', 'dd', 1, base=12)
 
-    assert m * k == 1
+    assert m * k is S.One
+    assert m * W == W / 1000
     assert k * k == M
     assert 1 / m == k
     assert k / m == M
@@ -25,7 +26,7 @@ def test_prefix_operations():
     assert dodeca * dodeca == 144
     assert 1 / dodeca == S.One / 12
     assert k / dodeca == S(1000) / 12
-    assert dodeca / dodeca == 1
+    assert dodeca / dodeca is S.One
 
     m = Quantity("fake_meter")
     SI.set_quantity_dimension(m, S.One)

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/physics/units/tests/test_prefixes.py
: '>>>>> End Test Output'
git checkout d3b4158dea271485e3daa11bf82e69b8dab348ce sympy/physics/units/tests/test_prefixes.py
