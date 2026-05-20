#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 73394f2b11321e03a5df199ec0196f27a728b0b0
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 73394f2b11321e03a5df199ec0196f27a728b0b0 lib/matplotlib/tests/test_offsetbox.py
git apply -v - <<'EOF_114329324912'
diff --git a/lib/matplotlib/tests/test_offsetbox.py b/lib/matplotlib/tests/test_offsetbox.py
--- a/lib/matplotlib/tests/test_offsetbox.py
+++ b/lib/matplotlib/tests/test_offsetbox.py
@@ -450,3 +450,11 @@ def test_paddedbox():
     pb = PaddedBox(ta, pad=15, draw_frame=True)
     ab = AnchoredOffsetbox('lower right', child=pb)
     ax.add_artist(ab)
+
+
+def test_remove_draggable():
+    fig, ax = plt.subplots()
+    an = ax.annotate("foo", (.5, .5))
+    an.draggable(True)
+    an.remove()
+    MouseEvent("button_release_event", fig.canvas, 1, 1)._process()

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA lib/matplotlib/tests/test_offsetbox.py
: '>>>>> End Test Output'
git checkout 73394f2b11321e03a5df199ec0196f27a728b0b0 lib/matplotlib/tests/test_offsetbox.py
