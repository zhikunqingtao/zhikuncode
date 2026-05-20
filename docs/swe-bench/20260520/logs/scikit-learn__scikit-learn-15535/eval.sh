#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 70b0ddea992c01df1a41588fa9e2d130fb6b13f8
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -v --no-use-pep517 --no-build-isolation -e .
git checkout 70b0ddea992c01df1a41588fa9e2d130fb6b13f8 sklearn/metrics/cluster/tests/test_common.py
git apply -v - <<'EOF_114329324912'
diff --git a/sklearn/metrics/cluster/tests/test_common.py b/sklearn/metrics/cluster/tests/test_common.py
--- a/sklearn/metrics/cluster/tests/test_common.py
+++ b/sklearn/metrics/cluster/tests/test_common.py
@@ -161,7 +161,9 @@ def generate_formats(y):
         y = np.array(y)
         yield y, 'array of ints'
         yield y.tolist(), 'list of ints'
-        yield [str(x) for x in y.tolist()], 'list of strs'
+        yield [str(x) + "-a" for x in y.tolist()], 'list of strs'
+        yield (np.array([str(x) + "-a" for x in y.tolist()], dtype=object),
+               'array of strs')
         yield y - 1, 'including negative ints'
         yield y + 1, 'strictly positive ints'
 

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA sklearn/metrics/cluster/tests/test_common.py
: '>>>>> End Test Output'
git checkout 70b0ddea992c01df1a41588fa9e2d130fb6b13f8 sklearn/metrics/cluster/tests/test_common.py
