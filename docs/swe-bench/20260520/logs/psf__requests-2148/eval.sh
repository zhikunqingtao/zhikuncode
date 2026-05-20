#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff fe693c492242ae532211e0c173324f09ca8cf227
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install .
git checkout fe693c492242ae532211e0c173324f09ca8cf227 test_requests.py
git apply -v - <<'EOF_114329324912'
diff --git a/test_requests.py b/test_requests.py
--- a/test_requests.py
+++ b/test_requests.py
@@ -18,7 +18,7 @@
 from requests.compat import (
     Morsel, cookielib, getproxies, str, urljoin, urlparse, is_py3, builtin_str)
 from requests.cookies import cookiejar_from_dict, morsel_to_cookie
-from requests.exceptions import InvalidURL, MissingSchema
+from requests.exceptions import InvalidURL, MissingSchema, ConnectionError
 from requests.models import PreparedRequest
 from requests.structures import CaseInsensitiveDict
 from requests.sessions import SessionRedirectMixin
@@ -720,6 +720,18 @@ def read_mock(amt, decode_content=None):
         assert next(iter(r))
         io.close()
 
+    def test_iter_content_handles_socket_error(self):
+        r = requests.Response()
+        import socket
+
+        class RawMock(object):
+            def stream(self, chunk_size, decode_content=None):
+                raise socket.error()
+
+        r.raw = RawMock()
+        with pytest.raises(ConnectionError):
+            list(r.iter_content())
+
     def test_response_decode_unicode(self):
         """
         When called with decode_unicode, Response.iter_content should always

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA test_requests.py
: '>>>>> End Test Output'
git checkout fe693c492242ae532211e0c173324f09ca8cf227 test_requests.py
