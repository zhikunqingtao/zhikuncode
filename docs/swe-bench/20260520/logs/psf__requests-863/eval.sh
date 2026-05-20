#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff a0df2cbb10419037d11d04352b3175405ab52941
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install .
git checkout a0df2cbb10419037d11d04352b3175405ab52941 tests/test_requests.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/test_requests.py b/tests/test_requests.py
--- a/tests/test_requests.py
+++ b/tests/test_requests.py
@@ -744,6 +744,40 @@ def add_bar_header(args):
             assert 'foo' in response.text
             assert 'bar' in response.text
 
+    def test_allow_list_of_hooks_to_register_hook(self):
+        """Issue 785: https://github.com/kennethreitz/requests/issues/785"""
+        def add_foo_header(args):
+            if not args.get('headers'):
+                args['headers'] = {}
+
+            args['headers'].update({
+                'X-Foo': 'foo'
+            })
+
+            return args
+
+        def add_bar_header(args):
+            if not args.get('headers'):
+                args['headers'] = {}
+
+            args['headers'].update({
+                'X-Bar': 'bar'
+            })
+
+            return args
+
+        def assert_hooks_are_callable(hooks):
+            for h in hooks['args']:
+                assert callable(h) is True
+
+        hooks = [add_foo_header, add_bar_header]
+        r = requests.models.Request()
+        r.register_hook('args', hooks)
+        assert_hooks_are_callable(r.hooks)
+
+        r = requests.models.Request(hooks={'args': hooks})
+        assert_hooks_are_callable(r.hooks)
+
     def test_session_persistent_cookies(self):
 
         s = requests.session()

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA tests/test_requests.py
: '>>>>> End Test Output'
git checkout a0df2cbb10419037d11d04352b3175405ab52941 tests/test_requests.py
