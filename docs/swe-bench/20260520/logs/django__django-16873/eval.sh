#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff fce90950bef348803fa7cc3e6bc65f4bce429b82
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout fce90950bef348803fa7cc3e6bc65f4bce429b82 tests/template_tests/filter_tests/test_join.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/template_tests/filter_tests/test_join.py b/tests/template_tests/filter_tests/test_join.py
--- a/tests/template_tests/filter_tests/test_join.py
+++ b/tests/template_tests/filter_tests/test_join.py
@@ -55,6 +55,22 @@ def test_join08(self):
         )
         self.assertEqual(output, "alpha & beta &amp; me")
 
+    @setup(
+        {
+            "join_autoescape_off": (
+                "{% autoescape off %}"
+                "{{ var_list|join:var_joiner }}"
+                "{% endautoescape %}"
+            ),
+        }
+    )
+    def test_join_autoescape_off(self):
+        var_list = ["<p>Hello World!</p>", "beta & me", "<script>Hi!</script>"]
+        context = {"var_list": var_list, "var_joiner": "<br/>"}
+        output = self.engine.render_to_string("join_autoescape_off", context)
+        expected_result = "<p>Hello World!</p><br/>beta & me<br/><script>Hi!</script>"
+        self.assertEqual(output, expected_result)
+
 
 class FunctionTests(SimpleTestCase):
     def test_list(self):
@@ -69,7 +85,7 @@ def test_autoescape(self):
     def test_autoescape_off(self):
         self.assertEqual(
             join(["<a>", "<img>", "</a>"], "<br>", autoescape=False),
-            "<a>&lt;br&gt;<img>&lt;br&gt;</a>",
+            "<a><br><img><br></a>",
         )
 
     def test_noniterable_arg(self):

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 template_tests.filter_tests.test_join
: '>>>>> End Test Output'
git checkout fce90950bef348803fa7cc3e6bc65f4bce429b82 tests/template_tests/filter_tests/test_join.py
