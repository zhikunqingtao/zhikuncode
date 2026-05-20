#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 29a86636a9c45ab5ac4d80ac76eaee497f460dce
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 29a86636a9c45ab5ac4d80ac76eaee497f460dce lib/mpl_toolkits/tests/test_mplot3d.py
git apply -v - <<'EOF_114329324912'
diff --git a/lib/mpl_toolkits/tests/test_mplot3d.py b/lib/mpl_toolkits/tests/test_mplot3d.py
--- a/lib/mpl_toolkits/tests/test_mplot3d.py
+++ b/lib/mpl_toolkits/tests/test_mplot3d.py
@@ -1812,6 +1812,28 @@ def test_scatter_spiral():
     fig.canvas.draw()
 
 
+def test_Poly3DCollection_get_facecolor():
+    # Smoke test to see that get_facecolor does not raise
+    # See GH#4067
+    y, x = np.ogrid[1:10:100j, 1:10:100j]
+    z2 = np.cos(x) ** 3 - np.sin(y) ** 2
+    fig = plt.figure()
+    ax = fig.add_subplot(111, projection='3d')
+    r = ax.plot_surface(x, y, z2, cmap='hot')
+    r.get_facecolor()
+
+
+def test_Poly3DCollection_get_edgecolor():
+    # Smoke test to see that get_edgecolor does not raise
+    # See GH#4067
+    y, x = np.ogrid[1:10:100j, 1:10:100j]
+    z2 = np.cos(x) ** 3 - np.sin(y) ** 2
+    fig = plt.figure()
+    ax = fig.add_subplot(111, projection='3d')
+    r = ax.plot_surface(x, y, z2, cmap='hot')
+    r.get_edgecolor()
+
+
 @pytest.mark.parametrize(
     "vertical_axis, proj_expected, axis_lines_expected, tickdirs_expected",
     [

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA lib/mpl_toolkits/tests/test_mplot3d.py
: '>>>>> End Test Output'
git checkout 29a86636a9c45ab5ac4d80ac76eaee497f460dce lib/mpl_toolkits/tests/test_mplot3d.py
