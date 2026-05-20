#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 04b15022e8d1f49af69d8a1e6cd678f31f1280ff
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 04b15022e8d1f49af69d8a1e6cd678f31f1280ff tests/forms_tests/tests/tests.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/forms_tests/tests/tests.py b/tests/forms_tests/tests/tests.py
--- a/tests/forms_tests/tests/tests.py
+++ b/tests/forms_tests/tests/tests.py
@@ -203,6 +203,46 @@ def test_initial_instance_value(self):
             """,
         )
 
+    def test_callable_default_hidden_widget_value_not_overridden(self):
+        class FieldWithCallableDefaultsModel(models.Model):
+            int_field = models.IntegerField(default=lambda: 1)
+            json_field = models.JSONField(default=dict)
+
+        class FieldWithCallableDefaultsModelForm(ModelForm):
+            class Meta:
+                model = FieldWithCallableDefaultsModel
+                fields = "__all__"
+
+        form = FieldWithCallableDefaultsModelForm(
+            data={
+                "initial-int_field": "1",
+                "int_field": "1000",
+                "initial-json_field": "{}",
+                "json_field": '{"key": "val"}',
+            }
+        )
+        form_html = form.as_p()
+        self.assertHTMLEqual(
+            form_html,
+            """
+            <p>
+            <label for="id_int_field">Int field:</label>
+            <input type="number" name="int_field" value="1000"
+                required id="id_int_field">
+            <input type="hidden" name="initial-int_field" value="1"
+                id="initial-id_int_field">
+            </p>
+            <p>
+            <label for="id_json_field">Json field:</label>
+            <textarea cols="40" id="id_json_field" name="json_field" required rows="10">
+            {&quot;key&quot;: &quot;val&quot;}
+            </textarea>
+            <input id="initial-id_json_field" name="initial-json_field" type="hidden"
+                value="{}">
+            </p>
+            """,
+        )
+
 
 class FormsModelTestCase(TestCase):
     def test_unicode_filename(self):

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 forms_tests.tests.tests
: '>>>>> End Test Output'
git checkout 04b15022e8d1f49af69d8a1e6cd678f31f1280ff tests/forms_tests/tests/tests.py
