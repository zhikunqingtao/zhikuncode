#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff b7a17b0ea0a2061bae752a3a2292007d41825814
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout b7a17b0ea0a2061bae752a3a2292007d41825814 tests/bulk_create/tests.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/bulk_create/tests.py b/tests/bulk_create/tests.py
--- a/tests/bulk_create/tests.py
+++ b/tests/bulk_create/tests.py
@@ -582,12 +582,16 @@ def _test_update_conflicts_two_fields(self, unique_fields):
             TwoFields(f1=1, f2=1, name="c"),
             TwoFields(f1=2, f2=2, name="d"),
         ]
-        TwoFields.objects.bulk_create(
+        results = TwoFields.objects.bulk_create(
             conflicting_objects,
             update_conflicts=True,
             unique_fields=unique_fields,
             update_fields=["name"],
         )
+        self.assertEqual(len(results), len(conflicting_objects))
+        if connection.features.can_return_rows_from_bulk_insert:
+            for instance in results:
+                self.assertIsNotNone(instance.pk)
         self.assertEqual(TwoFields.objects.count(), 2)
         self.assertCountEqual(
             TwoFields.objects.values("f1", "f2", "name"),
@@ -619,7 +623,6 @@ def test_update_conflicts_unique_fields_pk(self):
                 TwoFields(f1=2, f2=2, name="b"),
             ]
         )
-        self.assertEqual(TwoFields.objects.count(), 2)
 
         obj1 = TwoFields.objects.get(f1=1)
         obj2 = TwoFields.objects.get(f1=2)
@@ -627,12 +630,16 @@ def test_update_conflicts_unique_fields_pk(self):
             TwoFields(pk=obj1.pk, f1=3, f2=3, name="c"),
             TwoFields(pk=obj2.pk, f1=4, f2=4, name="d"),
         ]
-        TwoFields.objects.bulk_create(
+        results = TwoFields.objects.bulk_create(
             conflicting_objects,
             update_conflicts=True,
             unique_fields=["pk"],
             update_fields=["name"],
         )
+        self.assertEqual(len(results), len(conflicting_objects))
+        if connection.features.can_return_rows_from_bulk_insert:
+            for instance in results:
+                self.assertIsNotNone(instance.pk)
         self.assertEqual(TwoFields.objects.count(), 2)
         self.assertCountEqual(
             TwoFields.objects.values("f1", "f2", "name"),
@@ -680,12 +687,16 @@ def _test_update_conflicts_unique_two_fields(self, unique_fields):
                 description=("Japan is an island country in East Asia."),
             ),
         ]
-        Country.objects.bulk_create(
+        results = Country.objects.bulk_create(
             new_data,
             update_conflicts=True,
             update_fields=["description"],
             unique_fields=unique_fields,
         )
+        self.assertEqual(len(results), len(new_data))
+        if connection.features.can_return_rows_from_bulk_insert:
+            for instance in results:
+                self.assertIsNotNone(instance.pk)
         self.assertEqual(Country.objects.count(), 6)
         self.assertCountEqual(
             Country.objects.values("iso_two_letter", "description"),
@@ -743,12 +754,16 @@ def _test_update_conflicts(self, unique_fields):
             UpsertConflict(number=2, rank=2, name="Olivia"),
             UpsertConflict(number=3, rank=1, name="Hannah"),
         ]
-        UpsertConflict.objects.bulk_create(
+        results = UpsertConflict.objects.bulk_create(
             conflicting_objects,
             update_conflicts=True,
             update_fields=["name", "rank"],
             unique_fields=unique_fields,
         )
+        self.assertEqual(len(results), len(conflicting_objects))
+        if connection.features.can_return_rows_from_bulk_insert:
+            for instance in results:
+                self.assertIsNotNone(instance.pk)
         self.assertEqual(UpsertConflict.objects.count(), 3)
         self.assertCountEqual(
             UpsertConflict.objects.values("number", "rank", "name"),
@@ -759,12 +774,16 @@ def _test_update_conflicts(self, unique_fields):
             ],
         )
 
-        UpsertConflict.objects.bulk_create(
+        results = UpsertConflict.objects.bulk_create(
             conflicting_objects + [UpsertConflict(number=4, rank=4, name="Mark")],
             update_conflicts=True,
             update_fields=["name", "rank"],
             unique_fields=unique_fields,
         )
+        self.assertEqual(len(results), 4)
+        if connection.features.can_return_rows_from_bulk_insert:
+            for instance in results:
+                self.assertIsNotNone(instance.pk)
         self.assertEqual(UpsertConflict.objects.count(), 4)
         self.assertCountEqual(
             UpsertConflict.objects.values("number", "rank", "name"),
@@ -803,12 +822,16 @@ def test_update_conflicts_unique_fields_update_fields_db_column(self):
             FieldsWithDbColumns(rank=1, name="c"),
             FieldsWithDbColumns(rank=2, name="d"),
         ]
-        FieldsWithDbColumns.objects.bulk_create(
+        results = FieldsWithDbColumns.objects.bulk_create(
             conflicting_objects,
             update_conflicts=True,
             unique_fields=["rank"],
             update_fields=["name"],
         )
+        self.assertEqual(len(results), len(conflicting_objects))
+        if connection.features.can_return_rows_from_bulk_insert:
+            for instance in results:
+                self.assertIsNotNone(instance.pk)
         self.assertEqual(FieldsWithDbColumns.objects.count(), 2)
         self.assertCountEqual(
             FieldsWithDbColumns.objects.values("rank", "name"),

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 bulk_create.tests
: '>>>>> End Test Output'
git checkout b7a17b0ea0a2061bae752a3a2292007d41825814 tests/bulk_create/tests.py
