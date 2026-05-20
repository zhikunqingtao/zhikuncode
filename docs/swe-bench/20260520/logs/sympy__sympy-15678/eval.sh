#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 31c68eef3ffef39e2e792b0ec92cd92b7010eb2a
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 31c68eef3ffef39e2e792b0ec92cd92b7010eb2a sympy/geometry/tests/test_util.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/geometry/tests/test_util.py b/sympy/geometry/tests/test_util.py
--- a/sympy/geometry/tests/test_util.py
+++ b/sympy/geometry/tests/test_util.py
@@ -1,5 +1,5 @@
-from sympy import Symbol, sqrt, Derivative, S
-from sympy.geometry import Point, Point2D, Line, Circle ,Polygon, Segment, convex_hull, intersection, centroid
+from sympy import Symbol, sqrt, Derivative, S, Function, exp
+from sympy.geometry import Point, Point2D, Line, Circle, Polygon, Segment, convex_hull, intersection, centroid
 from sympy.geometry.util import idiff, closest_points, farthest_points, _ordered_points
 from sympy.solvers.solvers import solve
 from sympy.utilities.pytest import raises
@@ -9,6 +9,8 @@ def test_idiff():
     x = Symbol('x', real=True)
     y = Symbol('y', real=True)
     t = Symbol('t', real=True)
+    f = Function('f')
+    g = Function('g')
     # the use of idiff in ellipse also provides coverage
     circ = x**2 + y**2 - 4
     ans = -3*x*(x**2 + y**2)/y**5
@@ -19,6 +21,10 @@ def test_idiff():
     assert ans.subs(y, solve(circ, y)[0]).equals(explicit)
     assert True in [sol.diff(x, 3).equals(explicit) for sol in solve(circ, y)]
     assert idiff(x + t + y, [y, t], x) == -Derivative(t, x) - 1
+    assert idiff(f(x) * exp(f(x)) - x * exp(x), f(x), x) == (x + 1) * exp(x - f(x))/(f(x) + 1)
+    assert idiff(f(x) - y * exp(x), [f(x), y], x) == (y + Derivative(y, x)) * exp(x)
+    assert idiff(f(x) - y * exp(x), [y, f(x)], x) == -y + exp(-x) * Derivative(f(x), x)
+    assert idiff(f(x) - g(x), [f(x), g(x)], x) == Derivative(g(x), x)
 
 
 def test_intersection():

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/geometry/tests/test_util.py
: '>>>>> End Test Output'
git checkout 31c68eef3ffef39e2e792b0ec92cd92b7010eb2a sympy/geometry/tests/test_util.py
