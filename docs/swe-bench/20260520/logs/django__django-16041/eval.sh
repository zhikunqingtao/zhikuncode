#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 6df9398cce063874ae4d59db126d4adacb0fa8d3
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 6df9398cce063874ae4d59db126d4adacb0fa8d3 tests/forms_tests/tests/test_formsets.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/forms_tests/tests/test_formsets.py b/tests/forms_tests/tests/test_formsets.py
--- a/tests/forms_tests/tests/test_formsets.py
+++ b/tests/forms_tests/tests/test_formsets.py
@@ -179,6 +179,10 @@ def test_form_kwargs_empty_form(self):
         self.assertTrue(hasattr(formset.empty_form, "custom_kwarg"))
         self.assertEqual(formset.empty_form.custom_kwarg, 1)
 
+    def test_empty_permitted_ignored_empty_form(self):
+        formset = ArticleFormSet(form_kwargs={"empty_permitted": False})
+        self.assertIs(formset.empty_form.empty_permitted, True)
+
     def test_formset_validation(self):
         # FormSet instances can also have an error attribute if validation failed for
         # any of the forms.

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 forms_tests.tests.test_formsets
: '>>>>> End Test Output'
git checkout 6df9398cce063874ae4d59db126d4adacb0fa8d3 tests/forms_tests/tests/test_formsets.py
