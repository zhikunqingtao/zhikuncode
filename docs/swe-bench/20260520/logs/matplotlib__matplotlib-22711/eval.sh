#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff f670fe78795b18eb1118707721852209cd77ad51
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout f670fe78795b18eb1118707721852209cd77ad51 lib/matplotlib/tests/test_widgets.py
git apply -v - <<'EOF_114329324912'
diff --git a/lib/matplotlib/tests/test_widgets.py b/lib/matplotlib/tests/test_widgets.py
--- a/lib/matplotlib/tests/test_widgets.py
+++ b/lib/matplotlib/tests/test_widgets.py
@@ -1105,19 +1105,30 @@ def test_range_slider(orientation):
     # Check initial value is set correctly
     assert_allclose(slider.val, (0.1, 0.34))
 
+    def handle_positions(slider):
+        if orientation == "vertical":
+            return [h.get_ydata()[0] for h in slider._handles]
+        else:
+            return [h.get_xdata()[0] for h in slider._handles]
+
     slider.set_val((0.2, 0.6))
     assert_allclose(slider.val, (0.2, 0.6))
+    assert_allclose(handle_positions(slider), (0.2, 0.6))
+
     box = slider.poly.get_extents().transformed(ax.transAxes.inverted())
     assert_allclose(box.get_points().flatten()[idx], [0.2, .25, 0.6, .75])
 
     slider.set_val((0.2, 0.1))
     assert_allclose(slider.val, (0.1, 0.2))
+    assert_allclose(handle_positions(slider), (0.1, 0.2))
 
     slider.set_val((-1, 10))
     assert_allclose(slider.val, (0, 1))
+    assert_allclose(handle_positions(slider), (0, 1))
 
     slider.reset()
-    assert_allclose(slider.val, [0.1, 0.34])
+    assert_allclose(slider.val, (0.1, 0.34))
+    assert_allclose(handle_positions(slider), (0.1, 0.34))
 
 
 def check_polygon_selector(event_sequence, expected_result, selections_count,

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA lib/matplotlib/tests/test_widgets.py
: '>>>>> End Test Output'
git checkout f670fe78795b18eb1118707721852209cd77ad51 lib/matplotlib/tests/test_widgets.py
