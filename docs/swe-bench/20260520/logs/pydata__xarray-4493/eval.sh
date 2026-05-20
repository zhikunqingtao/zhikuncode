#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff a5f53e203c52a7605d5db799864046471115d04f
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout a5f53e203c52a7605d5db799864046471115d04f xarray/tests/test_dask.py xarray/tests/test_dataset.py xarray/tests/test_interp.py xarray/tests/test_variable.py
git apply -v - <<'EOF_114329324912'
diff --git a/xarray/tests/test_dask.py b/xarray/tests/test_dask.py
--- a/xarray/tests/test_dask.py
+++ b/xarray/tests/test_dask.py
@@ -1233,7 +1233,7 @@ def test_map_blocks_to_array(map_ds):
         lambda x: x.drop_vars("x"),
         lambda x: x.expand_dims(k=[1, 2, 3]),
         lambda x: x.expand_dims(k=3),
-        lambda x: x.assign_coords(new_coord=("y", x.y * 2)),
+        lambda x: x.assign_coords(new_coord=("y", x.y.data * 2)),
         lambda x: x.astype(np.int32),
         lambda x: x.x,
     ],
diff --git a/xarray/tests/test_dataset.py b/xarray/tests/test_dataset.py
--- a/xarray/tests/test_dataset.py
+++ b/xarray/tests/test_dataset.py
@@ -4959,13 +4959,13 @@ def test_reduce_keepdims(self):
         # Coordinates involved in the reduction should be removed
         actual = ds.mean(keepdims=True)
         expected = Dataset(
-            {"a": (["x", "y"], np.mean(ds.a, keepdims=True))}, coords={"c": ds.c}
+            {"a": (["x", "y"], np.mean(ds.a, keepdims=True).data)}, coords={"c": ds.c}
         )
         assert_identical(expected, actual)
 
         actual = ds.mean("x", keepdims=True)
         expected = Dataset(
-            {"a": (["x", "y"], np.mean(ds.a, axis=0, keepdims=True))},
+            {"a": (["x", "y"], np.mean(ds.a, axis=0, keepdims=True).data)},
             coords={"y": ds.y, "c": ds.c},
         )
         assert_identical(expected, actual)
diff --git a/xarray/tests/test_interp.py b/xarray/tests/test_interp.py
--- a/xarray/tests/test_interp.py
+++ b/xarray/tests/test_interp.py
@@ -190,7 +190,7 @@ def func(obj, dim, new_x):
             "w": xdest["w"],
             "z2": xdest["z2"],
             "y": da["y"],
-            "x": (("z", "w"), xdest),
+            "x": (("z", "w"), xdest.data),
             "x2": (("z", "w"), func(da["x2"], "x", xdest)),
         },
     )
diff --git a/xarray/tests/test_variable.py b/xarray/tests/test_variable.py
--- a/xarray/tests/test_variable.py
+++ b/xarray/tests/test_variable.py
@@ -8,7 +8,7 @@
 import pytest
 import pytz
 
-from xarray import Coordinate, Dataset, IndexVariable, Variable, set_options
+from xarray import Coordinate, DataArray, Dataset, IndexVariable, Variable, set_options
 from xarray.core import dtypes, duck_array_ops, indexing
 from xarray.core.common import full_like, ones_like, zeros_like
 from xarray.core.indexing import (
@@ -1081,6 +1081,9 @@ def test_as_variable(self):
         td = np.array([timedelta(days=x) for x in range(10)])
         assert as_variable(td, "time").dtype.kind == "m"
 
+        with pytest.warns(DeprecationWarning):
+            as_variable(("x", DataArray([])))
+
     def test_repr(self):
         v = Variable(["time", "x"], [[1, 2, 3], [4, 5, 6]], {"foo": "bar"})
         expected = dedent(

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA xarray/tests/test_dask.py xarray/tests/test_dataset.py xarray/tests/test_interp.py xarray/tests/test_variable.py
: '>>>>> End Test Output'
git checkout a5f53e203c52a7605d5db799864046471115d04f xarray/tests/test_dask.py xarray/tests/test_dataset.py xarray/tests/test_interp.py xarray/tests/test_variable.py
