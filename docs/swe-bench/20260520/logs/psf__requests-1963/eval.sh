#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 110048f9837f8441ea536804115e80b69f400277
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install .
git checkout 110048f9837f8441ea536804115e80b69f400277 test_requests.py
git apply -v - <<'EOF_114329324912'
diff --git a/test_requests.py b/test_requests.py
--- a/test_requests.py
+++ b/test_requests.py
@@ -8,6 +8,7 @@
 import os
 import pickle
 import unittest
+import collections
 
 import requests
 import pytest
@@ -18,6 +19,7 @@
 from requests.cookies import cookiejar_from_dict, morsel_to_cookie
 from requests.exceptions import InvalidURL, MissingSchema
 from requests.structures import CaseInsensitiveDict
+from requests.sessions import SessionRedirectMixin
 
 try:
     import StringIO
@@ -1187,5 +1189,64 @@ def test_stream_timeout(self):
             assert 'Read timed out' in e.args[0].args[0]
 
 
+SendCall = collections.namedtuple('SendCall', ('args', 'kwargs'))
+
+
+class RedirectSession(SessionRedirectMixin):
+    def __init__(self, order_of_redirects):
+        self.redirects = order_of_redirects
+        self.calls = []
+        self.max_redirects = 30
+        self.cookies = {}
+        self.trust_env = False
+
+    def send(self, *args, **kwargs):
+        self.calls.append(SendCall(args, kwargs))
+        return self.build_response()
+
+    def build_response(self):
+        request = self.calls[-1].args[0]
+        r = requests.Response()
+
+        try:
+            r.status_code = int(self.redirects.pop(0))
+        except IndexError:
+            r.status_code = 200
+
+        r.headers = CaseInsensitiveDict({'Location': '/'})
+        r.raw = self._build_raw()
+        r.request = request
+        return r
+
+    def _build_raw(self):
+        string = StringIO.StringIO('')
+        setattr(string, 'release_conn', lambda *args: args)
+        return string
+
+
+class TestRedirects:
+    default_keyword_args = {
+        'stream': False,
+        'verify': True,
+        'cert': None,
+        'timeout': None,
+        'allow_redirects': False,
+        'proxies': None,
+    }
+
+    def test_requests_are_updated_each_time(self):
+        session = RedirectSession([303, 307])
+        prep = requests.Request('POST', 'http://httpbin.org/post').prepare()
+        r0 = session.send(prep)
+        assert r0.request.method == 'POST'
+        assert session.calls[-1] == SendCall((r0.request,), {})
+        redirect_generator = session.resolve_redirects(r0, prep)
+        for response in redirect_generator:
+            assert response.request.method == 'GET'
+            send_call = SendCall((response.request,),
+                                 TestRedirects.default_keyword_args)
+            assert session.calls[-1] == send_call
+
+
 if __name__ == '__main__':
     unittest.main()

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA test_requests.py
: '>>>>> End Test Output'
git checkout 110048f9837f8441ea536804115e80b69f400277 test_requests.py
