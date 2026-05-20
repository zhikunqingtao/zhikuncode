#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff b30c0081d4d8a31ab7dc7f72a4c7099af606ef29
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout b30c0081d4d8a31ab7dc7f72a4c7099af606ef29 tests/migrations/test_writer.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/migrations/test_writer.py b/tests/migrations/test_writer.py
--- a/tests/migrations/test_writer.py
+++ b/tests/migrations/test_writer.py
@@ -413,6 +413,14 @@ def test_serialize_enum_flags(self):
             "(2, migrations.test_writer.IntFlagEnum['B'])], "
             "default=migrations.test_writer.IntFlagEnum['A'])",
         )
+        self.assertSerializedResultEqual(
+            IntFlagEnum.A | IntFlagEnum.B,
+            (
+                "migrations.test_writer.IntFlagEnum['A'] | "
+                "migrations.test_writer.IntFlagEnum['B']",
+                {"import migrations.test_writer"},
+            ),
+        )
 
     def test_serialize_choices(self):
         class TextChoices(models.TextChoices):

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 migrations.test_writer
: '>>>>> End Test Output'
git checkout b30c0081d4d8a31ab7dc7f72a4c7099af606ef29 tests/migrations/test_writer.py
