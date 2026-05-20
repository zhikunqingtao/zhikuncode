#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff d57aaf064041fe52c0fa357639b069100f8b28e1
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout d57aaf064041fe52c0fa357639b069100f8b28e1 sympy/physics/vector/tests/test_point.py
git apply -v - <<'EOF_114329324912'
diff --git a/sympy/physics/vector/tests/test_point.py b/sympy/physics/vector/tests/test_point.py
--- a/sympy/physics/vector/tests/test_point.py
+++ b/sympy/physics/vector/tests/test_point.py
@@ -126,3 +126,107 @@ def test_point_partial_velocity():
     assert p.partial_velocity(N, u1) == A.x
     assert p.partial_velocity(N, u1, u2) == (A.x, N.y)
     raises(ValueError, lambda: p.partial_velocity(A, u1))
+
+def test_point_vel(): #Basic functionality
+    q1, q2 = dynamicsymbols('q1 q2')
+    N = ReferenceFrame('N')
+    B = ReferenceFrame('B')
+    Q = Point('Q')
+    O = Point('O')
+    Q.set_pos(O, q1 * N.x)
+    raises(ValueError , lambda: Q.vel(N)) # Velocity of O in N is not defined
+    O.set_vel(N, q2 * N.y)
+    assert O.vel(N) == q2 * N.y
+    raises(ValueError , lambda : O.vel(B)) #Velocity of O is not defined in B
+
+def test_auto_point_vel():
+    t = dynamicsymbols._t
+    q1, q2 = dynamicsymbols('q1 q2')
+    N = ReferenceFrame('N')
+    B = ReferenceFrame('B')
+    O = Point('O')
+    Q = Point('Q')
+    Q.set_pos(O, q1 * N.x)
+    O.set_vel(N, q2 * N.y)
+    assert Q.vel(N) == q1.diff(t) * N.x + q2 * N.y  # Velocity of Q using O
+    P1 = Point('P1')
+    P1.set_pos(O, q1 * B.x)
+    P2 = Point('P2')
+    P2.set_pos(P1, q2 * B.z)
+    raises(ValueError, lambda : P2.vel(B)) # O's velocity is defined in different frame, and no
+    #point in between has its velocity defined
+    raises(ValueError, lambda: P2.vel(N)) # Velocity of O not defined in N
+
+def test_auto_point_vel_multiple_point_path():
+    t = dynamicsymbols._t
+    q1, q2 = dynamicsymbols('q1 q2')
+    B = ReferenceFrame('B')
+    P = Point('P')
+    P.set_vel(B, q1 * B.x)
+    P1 = Point('P1')
+    P1.set_pos(P, q2 * B.y)
+    P1.set_vel(B, q1 * B.z)
+    P2 = Point('P2')
+    P2.set_pos(P1, q1 * B.z)
+    P3 = Point('P3')
+    P3.set_pos(P2, 10 * q1 * B.y)
+    assert P3.vel(B) == 10 * q1.diff(t) * B.y + (q1 + q1.diff(t)) * B.z
+
+def test_auto_vel_dont_overwrite():
+    t = dynamicsymbols._t
+    q1, q2, u1 = dynamicsymbols('q1, q2, u1')
+    N = ReferenceFrame('N')
+    P = Point('P1')
+    P.set_vel(N, u1 * N.x)
+    P1 = Point('P1')
+    P1.set_pos(P, q2 * N.y)
+    assert P1.vel(N) == q2.diff(t) * N.y + u1 * N.x
+    assert P.vel(N) == u1 * N.x
+    P1.set_vel(N, u1 * N.z)
+    assert P1.vel(N) == u1 * N.z
+
+def test_auto_point_vel_if_tree_has_vel_but_inappropriate_pos_vector():
+    q1, q2 = dynamicsymbols('q1 q2')
+    B = ReferenceFrame('B')
+    S = ReferenceFrame('S')
+    P = Point('P')
+    P.set_vel(B, q1 * B.x)
+    P1 = Point('P1')
+    P1.set_pos(P, S.y)
+    raises(ValueError, lambda : P1.vel(B)) # P1.pos_from(P) can't be expressed in B
+    raises(ValueError, lambda : P1.vel(S)) # P.vel(S) not defined
+
+def test_auto_point_vel_shortest_path():
+    t = dynamicsymbols._t
+    q1, q2, u1, u2 = dynamicsymbols('q1 q2 u1 u2')
+    B = ReferenceFrame('B')
+    P = Point('P')
+    P.set_vel(B, u1 * B.x)
+    P1 = Point('P1')
+    P1.set_pos(P, q2 * B.y)
+    P1.set_vel(B, q1 * B.z)
+    P2 = Point('P2')
+    P2.set_pos(P1, q1 * B.z)
+    P3 = Point('P3')
+    P3.set_pos(P2, 10 * q1 * B.y)
+    P4 = Point('P4')
+    P4.set_pos(P3, q1 * B.x)
+    O = Point('O')
+    O.set_vel(B, u2 * B.y)
+    O1 = Point('O1')
+    O1.set_pos(O, q2 * B.z)
+    P4.set_pos(O1, q1 * B.x + q2 * B.z)
+    assert P4.vel(B) == q1.diff(t) * B.x + u2 * B.y + 2 * q2.diff(t) * B.z
+
+def test_auto_point_vel_connected_frames():
+    t = dynamicsymbols._t
+    q, q1, q2, u = dynamicsymbols('q q1 q2 u')
+    N = ReferenceFrame('N')
+    B = ReferenceFrame('B')
+    O = Point('O')
+    O.set_vel(N, u * N.x)
+    P = Point('P')
+    P.set_pos(O, q1 * N.x + q2 * B.y)
+    raises(ValueError, lambda: P.vel(N))
+    N.orient(B, 'Axis', (q, B.x))
+    assert P.vel(N) == (u + q1.diff(t)) * N.x + q2.diff(t) * B.y - q2 * q.diff(t) * B.z

EOF_114329324912
: '>>>>> Start Test Output'
PYTHONWARNINGS='ignore::UserWarning,ignore::SyntaxWarning' bin/test -C --verbose sympy/physics/vector/tests/test_point.py
: '>>>>> End Test Output'
git checkout d57aaf064041fe52c0fa357639b069100f8b28e1 sympy/physics/vector/tests/test_point.py
