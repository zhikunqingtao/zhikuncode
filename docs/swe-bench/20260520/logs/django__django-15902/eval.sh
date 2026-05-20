#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 44c24bf02835323d5418512ebe8e76166739ebf8
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 44c24bf02835323d5418512ebe8e76166739ebf8 tests/forms_tests/tests/test_formsets.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/forms_tests/tests/test_formsets.py b/tests/forms_tests/tests/test_formsets.py
--- a/tests/forms_tests/tests/test_formsets.py
+++ b/tests/forms_tests/tests/test_formsets.py
@@ -1910,3 +1910,14 @@ def test_warning(self):
             ChoiceFormSet = formset_factory(Choice)
             formset = ChoiceFormSet()
             str(formset)
+
+    def test_no_management_form_warning(self):
+        """
+        Management forms are already rendered with the new div template.
+        """
+        with isolate_lru_cache(get_default_renderer), self.settings(
+            FORM_RENDERER="django.forms.renderers.DjangoTemplates"
+        ):
+            ChoiceFormSet = formset_factory(Choice, formset=BaseFormSet)
+            formset = ChoiceFormSet()
+            str(formset.management_form)

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 forms_tests.tests.test_formsets
: '>>>>> End Test Output'
git checkout 44c24bf02835323d5418512ebe8e76166739ebf8 tests/forms_tests/tests/test_formsets.py
