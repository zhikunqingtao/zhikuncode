#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 36453b95b13079296776d11b09cab2567ea3e703
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install .
git checkout 36453b95b13079296776d11b09cab2567ea3e703 tests/test_requests.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/test_requests.py b/tests/test_requests.py
--- a/tests/test_requests.py
+++ b/tests/test_requests.py
@@ -980,6 +980,13 @@ def test_response_decode_unicode(self):
         chunks = r.iter_content(decode_unicode=True)
         assert all(isinstance(chunk, str) for chunk in chunks)
 
+        # check for encoding value of None
+        r = requests.Response()
+        r.raw = io.BytesIO(b'the content')
+        r.encoding = None
+        chunks = r.iter_content(decode_unicode=True)
+        assert all(isinstance(chunk, str) for chunk in chunks)
+
     def test_response_chunk_size_int(self):
         """Ensure that chunk_size is passed as an integer, otherwise
         raise a TypeError.

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA tests/test_requests.py
: '>>>>> End Test Output'
git checkout 36453b95b13079296776d11b09cab2567ea3e703 tests/test_requests.py
