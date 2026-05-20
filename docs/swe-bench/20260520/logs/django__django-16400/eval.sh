#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 0bd2c0c9015b53c41394a1c0989afbfd94dc2830
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 0bd2c0c9015b53c41394a1c0989afbfd94dc2830 tests/auth_tests/test_management.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/auth_tests/test_management.py b/tests/auth_tests/test_management.py
--- a/tests/auth_tests/test_management.py
+++ b/tests/auth_tests/test_management.py
@@ -1485,3 +1485,22 @@ def test_permission_with_proxy_content_type_created(self):
                 codename=codename,
             ).exists()
         )
+
+
+class DefaultDBRouter:
+    """Route all writes to default."""
+
+    def db_for_write(self, model, **hints):
+        return "default"
+
+
+@override_settings(DATABASE_ROUTERS=[DefaultDBRouter()])
+class CreatePermissionsMultipleDatabasesTests(TestCase):
+    databases = {"default", "other"}
+
+    def test_set_permissions_fk_to_using_parameter(self):
+        Permission.objects.using("other").delete()
+        with self.assertNumQueries(6, using="other") as captured_queries:
+            create_permissions(apps.get_app_config("auth"), verbosity=0, using="other")
+        self.assertIn("INSERT INTO", captured_queries[-1]["sql"].upper())
+        self.assertGreater(Permission.objects.using("other").count(), 0)

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 auth_tests.test_management
: '>>>>> End Test Output'
git checkout 0bd2c0c9015b53c41394a1c0989afbfd94dc2830 tests/auth_tests/test_management.py
