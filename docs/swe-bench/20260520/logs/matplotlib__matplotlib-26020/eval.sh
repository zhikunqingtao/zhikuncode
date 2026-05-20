#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff f6a781f77f5ddf1204c60ca7c544809407d4a807
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout f6a781f77f5ddf1204c60ca7c544809407d4a807 lib/mpl_toolkits/axes_grid1/tests/test_axes_grid1.py
git apply -v - <<'EOF_114329324912'
diff --git a/lib/mpl_toolkits/axes_grid1/tests/test_axes_grid1.py b/lib/mpl_toolkits/axes_grid1/tests/test_axes_grid1.py
--- a/lib/mpl_toolkits/axes_grid1/tests/test_axes_grid1.py
+++ b/lib/mpl_toolkits/axes_grid1/tests/test_axes_grid1.py
@@ -767,3 +767,7 @@ def test_anchored_locator_base_call():
     axins.set(xticks=[], yticks=[])
 
     axins.imshow(Z, extent=extent, origin="lower")
+
+
+def test_grid_with_axes_class_not_overriding_axis():
+    Grid(plt.figure(), 111, (2, 2), axes_class=mpl.axes.Axes)

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA lib/mpl_toolkits/axes_grid1/tests/test_axes_grid1.py
: '>>>>> End Test Output'
git checkout f6a781f77f5ddf1204c60ca7c544809407d4a807 lib/mpl_toolkits/axes_grid1/tests/test_axes_grid1.py
