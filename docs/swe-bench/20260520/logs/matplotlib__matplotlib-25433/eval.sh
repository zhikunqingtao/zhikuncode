#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 7eafdd8af3c523c1c77b027d378fb337dd489f18
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 7eafdd8af3c523c1c77b027d378fb337dd489f18 lib/matplotlib/tests/test_backend_bases.py
git apply -v - <<'EOF_114329324912'
diff --git a/lib/matplotlib/tests/test_backend_bases.py b/lib/matplotlib/tests/test_backend_bases.py
--- a/lib/matplotlib/tests/test_backend_bases.py
+++ b/lib/matplotlib/tests/test_backend_bases.py
@@ -95,6 +95,16 @@ def test_non_gui_warning(monkeypatch):
                 in str(rec[0].message))
 
 
+def test_grab_clear():
+    fig, ax = plt.subplots()
+
+    fig.canvas.grab_mouse(ax)
+    assert fig.canvas.mouse_grabber == ax
+
+    fig.clear()
+    assert fig.canvas.mouse_grabber is None
+
+
 @pytest.mark.parametrize(
     "x, y", [(42, 24), (None, 42), (None, None), (200, 100.01), (205.75, 2.0)])
 def test_location_event_position(x, y):

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA lib/matplotlib/tests/test_backend_bases.py
: '>>>>> End Test Output'
git checkout 7eafdd8af3c523c1c77b027d378fb337dd489f18 lib/matplotlib/tests/test_backend_bases.py
