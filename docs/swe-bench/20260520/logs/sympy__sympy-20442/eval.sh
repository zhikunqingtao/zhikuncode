#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 1abbc0ac3e552cb184317194e5d5c5b9dd8fb640
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 1abbc0ac3e552cb184317194e5d5c5b9dd8fb640 sympy/physics/units/tests/test_quantities.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/physics/units/tests/test_quantities.py b/sympy/physics/units/tests/test_quantities.py
--- a/sympy/physics/units/tests/test_quantities.py
+++ b/sympy/physics/units/tests/test_quantities.py
@@ -1,7 +1,7 @@
 from sympy import (Abs, Add, Function, Number, Rational, S, Symbol,
                    diff, exp, integrate, log, sin, sqrt, symbols)
 from sympy.physics.units import (amount_of_substance, convert_to, find_unit,
-                                 volume, kilometer)
+                                 volume, kilometer, joule)
 from sympy.physics.units.definitions import (amu, au, centimeter, coulomb,
     day, foot, grams, hour, inch, kg, km, m, meter, millimeter,
     minute, quart, s, second, speed_of_light, bit,
@@ -45,6 +45,10 @@ def test_convert_to():
     assert q.convert_to(s) == q
     assert speed_of_light.convert_to(m) == speed_of_light
 
+    expr = joule*second
+    conv = convert_to(expr, joule)
+    assert conv == joule*second
+
 
 def test_Quantity_definition():
     q = Quantity("s10", abbrev="sabbr")

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/physics/units/tests/test_quantities.py
: '>>>>> End Test Output'
git checkout 1abbc0ac3e552cb184317194e5d5c5b9dd8fb640 sympy/physics/units/tests/test_quantities.py
