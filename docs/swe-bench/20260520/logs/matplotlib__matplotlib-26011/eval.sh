#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 00afcc0c6d4d2e4390338127f05b8f4fdb4e7087
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 00afcc0c6d4d2e4390338127f05b8f4fdb4e7087 lib/matplotlib/tests/test_axes.py
git apply -v - <<'EOF_114329324912'
diff --git a/lib/matplotlib/tests/test_axes.py b/lib/matplotlib/tests/test_axes.py
--- a/lib/matplotlib/tests/test_axes.py
+++ b/lib/matplotlib/tests/test_axes.py
@@ -8794,3 +8794,12 @@ def test_set_secondary_axis_color():
     assert mcolors.same_color(sax.xaxis.get_tick_params()["color"], "red")
     assert mcolors.same_color(sax.xaxis.get_tick_params()["labelcolor"], "red")
     assert mcolors.same_color(sax.xaxis.label.get_color(), "red")
+
+
+def test_xylim_changed_shared():
+    fig, axs = plt.subplots(2, sharex=True, sharey=True)
+    events = []
+    axs[1].callbacks.connect("xlim_changed", events.append)
+    axs[1].callbacks.connect("ylim_changed", events.append)
+    axs[0].set(xlim=[1, 3], ylim=[2, 4])
+    assert events == [axs[1], axs[1]]

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA lib/matplotlib/tests/test_axes.py
: '>>>>> End Test Output'
git checkout 00afcc0c6d4d2e4390338127f05b8f4fdb4e7087 lib/matplotlib/tests/test_axes.py
