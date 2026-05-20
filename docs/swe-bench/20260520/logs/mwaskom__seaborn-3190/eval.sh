#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 4a9e54962a29c12a8b103d75f838e0e795a6974d
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .[dev]
git checkout 4a9e54962a29c12a8b103d75f838e0e795a6974d tests/_core/test_scales.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/_core/test_scales.py b/tests/_core/test_scales.py
--- a/tests/_core/test_scales.py
+++ b/tests/_core/test_scales.py
@@ -90,6 +90,12 @@ def test_interval_with_range_norm_and_transform(self, x):
         s = Continuous((2, 3), (10, 100), "log")._setup(x, IntervalProperty())
         assert_array_equal(s(x), [1, 2, 3])
 
+    def test_interval_with_bools(self):
+
+        x = pd.Series([True, False, False])
+        s = Continuous()._setup(x, IntervalProperty())
+        assert_array_equal(s(x), [1, 0, 0])
+
     def test_color_defaults(self, x):
 
         cmap = color_palette("ch:", as_cmap=True)

EOF_114329324912
: '>>>>> Start Test Output'
pytest --no-header -rA tests/_core/test_scales.py
: '>>>>> End Test Output'
git checkout 4a9e54962a29c12a8b103d75f838e0e795a6974d tests/_core/test_scales.py
