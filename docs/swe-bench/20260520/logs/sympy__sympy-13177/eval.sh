#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 662cfb818e865f580e18b59efbb3540c34232beb
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 662cfb818e865f580e18b59efbb3540c34232beb sympy/core/tests/test_numbers.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/core/tests/test_numbers.py b/sympy/core/tests/test_numbers.py
--- a/sympy/core/tests/test_numbers.py
+++ b/sympy/core/tests/test_numbers.py
@@ -8,6 +8,7 @@
 from sympy.core.logic import fuzzy_not
 from sympy.core.numbers import (igcd, ilcm, igcdex, seterr, _intcache,
     igcd2, igcd_lehmer, mpf_norm, comp, mod_inverse)
+from sympy.core.mod import Mod
 from sympy.utilities.decorator import conserve_mpmath_dps
 from sympy.utilities.iterables import permutations
 from sympy.utilities.pytest import XFAIL, raises
@@ -121,6 +122,20 @@ def test_mod():
     assert Integer(10) % 4 == Integer(2)
     assert 15 % Integer(4) == Integer(3)
 
+    h = Symbol('h')
+    m = h ** 2 % h
+    k = h ** -2 % h
+    l = Symbol('l', integer=True)
+    p = Symbol('p', integer=True, positive=True)
+    q = Symbol('q', integer=True, negative=True)
+
+    assert m == h * (h % 1)
+    assert k == Mod(h ** -2, h, evaluate=False)
+    assert Mod(l ** p, l) == 0
+    assert Mod(l ** 2, l) == 0
+    assert (l ** q % l) == Mod(l ** q, l, evaluate=False)
+    assert (l ** -2 % l) == Mod(l ** -2, l, evaluate=False)
+
 
 def test_divmod():
     assert divmod(S(12), S(8)) == Tuple(1, 4)

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/core/tests/test_numbers.py
: '>>>>> End Test Output'
git checkout 662cfb818e865f580e18b59efbb3540c34232beb sympy/core/tests/test_numbers.py
