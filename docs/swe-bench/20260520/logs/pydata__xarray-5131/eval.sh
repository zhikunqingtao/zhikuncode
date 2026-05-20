#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff e56905889c836c736152b11a7e6117a229715975
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout e56905889c836c736152b11a7e6117a229715975 xarray/tests/test_groupby.py
git apply -v - <<'EOF_114329324912'
diff --git a/xarray/tests/test_groupby.py b/xarray/tests/test_groupby.py
--- a/xarray/tests/test_groupby.py
+++ b/xarray/tests/test_groupby.py
@@ -388,7 +388,7 @@ def test_da_groupby_assign_coords():
 def test_groupby_repr(obj, dim):
     actual = repr(obj.groupby(dim))
     expected = "%sGroupBy" % obj.__class__.__name__
-    expected += ", grouped over %r " % dim
+    expected += ", grouped over %r" % dim
     expected += "\n%r groups with labels " % (len(np.unique(obj[dim])))
     if dim == "x":
         expected += "1, 2, 3, 4, 5."
@@ -405,7 +405,7 @@ def test_groupby_repr(obj, dim):
 def test_groupby_repr_datetime(obj):
     actual = repr(obj.groupby("t.month"))
     expected = "%sGroupBy" % obj.__class__.__name__
-    expected += ", grouped over 'month' "
+    expected += ", grouped over 'month'"
     expected += "\n%r groups with labels " % (len(np.unique(obj.t.dt.month)))
     expected += "1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12."
     assert actual == expected

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA xarray/tests/test_groupby.py
: '>>>>> End Test Output'
git checkout e56905889c836c736152b11a7e6117a229715975 xarray/tests/test_groupby.py
