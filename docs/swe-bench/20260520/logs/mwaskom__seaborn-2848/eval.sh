#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 94621cef29f80282436d73e8d2c0aa76dab81273
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .[dev]
git checkout 94621cef29f80282436d73e8d2c0aa76dab81273 tests/test_relational.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/test_relational.py b/tests/test_relational.py
--- a/tests/test_relational.py
+++ b/tests/test_relational.py
@@ -9,6 +9,7 @@
 
 from seaborn.external.version import Version
 from seaborn.palettes import color_palette
+from seaborn._oldcore import categorical_order
 
 from seaborn.relational import (
     _RelationalPlotter,
@@ -1623,6 +1624,16 @@ def test_supplied_color_array(self, long_df):
         _draw_figure(ax.figure)
         assert_array_equal(ax.collections[0].get_facecolors(), colors)
 
+    def test_hue_order(self, long_df):
+
+        order = categorical_order(long_df["a"])
+        unused = order.pop()
+
+        ax = scatterplot(data=long_df, x="x", y="y", hue="a", hue_order=order)
+        points = ax.collections[0]
+        assert (points.get_facecolors()[long_df["a"] == unused] == 0).all()
+        assert [t.get_text() for t in ax.legend_.texts] == order
+
     def test_linewidths(self, long_df):
 
         f, ax = plt.subplots()

EOF_114329324912
: '>>>>> Start Test Output'
pytest --no-header -rA tests/test_relational.py
: '>>>>> End Test Output'
git checkout 94621cef29f80282436d73e8d2c0aa76dab81273 tests/test_relational.py
