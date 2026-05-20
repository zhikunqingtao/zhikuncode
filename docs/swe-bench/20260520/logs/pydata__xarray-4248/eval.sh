#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 98dc1f4ea18738492e074e9e51ddfed5cd30ab94
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 98dc1f4ea18738492e074e9e51ddfed5cd30ab94 xarray/tests/test_formatting.py
git apply -v - <<'EOF_114329324912'
diff --git a/xarray/tests/test_formatting.py b/xarray/tests/test_formatting.py
--- a/xarray/tests/test_formatting.py
+++ b/xarray/tests/test_formatting.py
@@ -7,6 +7,7 @@
 
 import xarray as xr
 from xarray.core import formatting
+from xarray.core.npcompat import IS_NEP18_ACTIVE
 
 from . import raises_regex
 
@@ -391,6 +392,44 @@ def test_array_repr(self):
         assert actual == expected
 
 
+@pytest.mark.skipif(not IS_NEP18_ACTIVE, reason="requires __array_function__")
+def test_inline_variable_array_repr_custom_repr():
+    class CustomArray:
+        def __init__(self, value, attr):
+            self.value = value
+            self.attr = attr
+
+        def _repr_inline_(self, width):
+            formatted = f"({self.attr}) {self.value}"
+            if len(formatted) > width:
+                formatted = f"({self.attr}) ..."
+
+            return formatted
+
+        def __array_function__(self, *args, **kwargs):
+            return NotImplemented
+
+        @property
+        def shape(self):
+            return self.value.shape
+
+        @property
+        def dtype(self):
+            return self.value.dtype
+
+        @property
+        def ndim(self):
+            return self.value.ndim
+
+    value = CustomArray(np.array([20, 40]), "m")
+    variable = xr.Variable("x", value)
+
+    max_width = 10
+    actual = formatting.inline_variable_array_repr(variable, max_width=10)
+
+    assert actual == value._repr_inline_(max_width)
+
+
 def test_set_numpy_options():
     original_options = np.get_printoptions()
     with formatting.set_numpy_options(threshold=10):

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA xarray/tests/test_formatting.py
: '>>>>> End Test Output'
git checkout 98dc1f4ea18738492e074e9e51ddfed5cd30ab94 xarray/tests/test_formatting.py
