#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 877c800f255ccaa7abde1fb944de45d1616f5cc9
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 877c800f255ccaa7abde1fb944de45d1616f5cc9 tests/inspectdb/models.py tests/inspectdb/tests.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/inspectdb/models.py b/tests/inspectdb/models.py
--- a/tests/inspectdb/models.py
+++ b/tests/inspectdb/models.py
@@ -9,6 +9,7 @@ class People(models.Model):
 
 class Message(models.Model):
     from_field = models.ForeignKey(People, models.CASCADE, db_column="from_id")
+    author = models.ForeignKey(People, models.CASCADE, related_name="message_authors")
 
 
 class PeopleData(models.Model):
diff --git a/tests/inspectdb/tests.py b/tests/inspectdb/tests.py
--- a/tests/inspectdb/tests.py
+++ b/tests/inspectdb/tests.py
@@ -433,6 +433,15 @@ def test_introspection_errors(self):
         # The error message depends on the backend
         self.assertIn("# The error was:", output)
 
+    def test_same_relations(self):
+        out = StringIO()
+        call_command("inspectdb", "inspectdb_message", stdout=out)
+        self.assertIn(
+            "author = models.ForeignKey('InspectdbPeople', models.DO_NOTHING, "
+            "related_name='inspectdbmessage_author_set')",
+            out.getvalue(),
+        )
+
 
 class InspectDBTransactionalTests(TransactionTestCase):
     available_apps = ["inspectdb"]

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 inspectdb.models inspectdb.tests
: '>>>>> End Test Output'
git checkout 877c800f255ccaa7abde1fb944de45d1616f5cc9 tests/inspectdb/models.py tests/inspectdb/tests.py
