#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 69356d20cfee9a81972dcbf93d8caf9eabe113e8
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 69356d20cfee9a81972dcbf93d8caf9eabe113e8 testing/test_skipping.py
git apply -v - <<'EOF_114329324912'
diff --git a/testing/test_skipping.py b/testing/test_skipping.py
--- a/testing/test_skipping.py
+++ b/testing/test_skipping.py
@@ -1341,7 +1341,7 @@ def test_func():
     )
     result = pytester.runpytest()
     result.stdout.fnmatch_lines(
-        ["*Using pytest.skip outside of a test is not allowed*"]
+        ["*Using pytest.skip outside of a test will skip the entire module*"]
     )
 
 

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA testing/test_skipping.py
: '>>>>> End Test Output'
git checkout 69356d20cfee9a81972dcbf93d8caf9eabe113e8 testing/test_skipping.py
