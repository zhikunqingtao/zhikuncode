#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff b7d05919865fc0c37a0164cf467d5d5513bd0ede
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout b7d05919865fc0c37a0164cf467d5d5513bd0ede lib/matplotlib/tests/test_matplotlib.py
git apply -v - <<'EOF_114329324912'
diff --git a/lib/matplotlib/tests/test_matplotlib.py b/lib/matplotlib/tests/test_matplotlib.py
--- a/lib/matplotlib/tests/test_matplotlib.py
+++ b/lib/matplotlib/tests/test_matplotlib.py
@@ -7,6 +7,16 @@
 import matplotlib
 
 
+@pytest.mark.parametrize('version_str, version_tuple', [
+    ('3.5.0', (3, 5, 0, 'final', 0)),
+    ('3.5.0rc2', (3, 5, 0, 'candidate', 2)),
+    ('3.5.0.dev820+g6768ef8c4c', (3, 5, 0, 'alpha', 820)),
+    ('3.5.0.post820+g6768ef8c4c', (3, 5, 1, 'alpha', 820)),
+])
+def test_parse_to_version_info(version_str, version_tuple):
+    assert matplotlib._parse_to_version_info(version_str) == version_tuple
+
+
 @pytest.mark.skipif(
     os.name == "nt", reason="chmod() doesn't work as is on Windows")
 @pytest.mark.skipif(os.name != "nt" and os.geteuid() == 0,

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA lib/matplotlib/tests/test_matplotlib.py
: '>>>>> End Test Output'
git checkout b7d05919865fc0c37a0164cf467d5d5513bd0ede lib/matplotlib/tests/test_matplotlib.py
