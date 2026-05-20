#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 1aefb24b37c30fba8fd79a744829ca16e252f340
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 1aefb24b37c30fba8fd79a744829ca16e252f340 testing/test_assertion.py
git apply -v - <<'EOF_114329324912'
diff --git a/testing/test_assertion.py b/testing/test_assertion.py
--- a/testing/test_assertion.py
+++ b/testing/test_assertion.py
@@ -331,6 +331,27 @@ def test_multiline_text_diff(self):
         assert "- spam" in diff
         assert "+ eggs" in diff
 
+    def test_bytes_diff_normal(self):
+        """Check special handling for bytes diff (#5260)"""
+        diff = callequal(b"spam", b"eggs")
+
+        assert diff == [
+            "b'spam' == b'eggs'",
+            "At index 0 diff: b's' != b'e'",
+            "Use -v to get the full diff",
+        ]
+
+    def test_bytes_diff_verbose(self):
+        """Check special handling for bytes diff (#5260)"""
+        diff = callequal(b"spam", b"eggs", verbose=True)
+        assert diff == [
+            "b'spam' == b'eggs'",
+            "At index 0 diff: b's' != b'e'",
+            "Full diff:",
+            "- b'spam'",
+            "+ b'eggs'",
+        ]
+
     def test_list(self):
         expl = callequal([0, 1], [0, 2])
         assert len(expl) > 1

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA testing/test_assertion.py
: '>>>>> End Test Output'
git checkout 1aefb24b37c30fba8fd79a744829ca16e252f340 testing/test_assertion.py
