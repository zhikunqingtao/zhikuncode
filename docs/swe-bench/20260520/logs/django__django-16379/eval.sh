#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 1d0fa848e084cad62d0bb6bde3b51e4862558e57
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 1d0fa848e084cad62d0bb6bde3b51e4862558e57 tests/cache/tests.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/cache/tests.py b/tests/cache/tests.py
--- a/tests/cache/tests.py
+++ b/tests/cache/tests.py
@@ -1762,6 +1762,12 @@ def test_empty_cache_file_considered_expired(self):
         with open(cache_file, "rb") as fh:
             self.assertIs(cache._is_expired(fh), True)
 
+    def test_has_key_race_handling(self):
+        self.assertIs(cache.add("key", "value"), True)
+        with mock.patch("builtins.open", side_effect=FileNotFoundError) as mocked_open:
+            self.assertIs(cache.has_key("key"), False)
+            mocked_open.assert_called_once()
+
 
 @unittest.skipUnless(RedisCache_params, "Redis backend not configured")
 @override_settings(

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 cache.tests
: '>>>>> End Test Output'
git checkout 1d0fa848e084cad62d0bb6bde3b51e4862558e57 tests/cache/tests.py
