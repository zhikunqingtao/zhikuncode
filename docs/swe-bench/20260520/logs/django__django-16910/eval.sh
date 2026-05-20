#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 4142739af1cda53581af4169dbe16d6cd5e26948
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 4142739af1cda53581af4169dbe16d6cd5e26948 tests/defer_regress/tests.py tests/select_related_onetoone/tests.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/defer_regress/tests.py b/tests/defer_regress/tests.py
--- a/tests/defer_regress/tests.py
+++ b/tests/defer_regress/tests.py
@@ -178,6 +178,16 @@ def test_reverse_one_to_one_relations(self):
             self.assertEqual(i.one_to_one_item.name, "second")
         with self.assertNumQueries(1):
             self.assertEqual(i.value, 42)
+        with self.assertNumQueries(1):
+            i = Item.objects.select_related("one_to_one_item").only(
+                "name", "one_to_one_item__item"
+            )[0]
+            self.assertEqual(i.one_to_one_item.pk, o2o.pk)
+            self.assertEqual(i.name, "first")
+        with self.assertNumQueries(1):
+            self.assertEqual(i.one_to_one_item.name, "second")
+        with self.assertNumQueries(1):
+            self.assertEqual(i.value, 42)
 
     def test_defer_with_select_related(self):
         item1 = Item.objects.create(name="first", value=47)
@@ -277,6 +287,28 @@ def test_defer_many_to_many_ignored(self):
         with self.assertNumQueries(1):
             self.assertEqual(Request.objects.defer("items").get(), request)
 
+    def test_only_many_to_many_ignored(self):
+        location = Location.objects.create()
+        request = Request.objects.create(location=location)
+        with self.assertNumQueries(1):
+            self.assertEqual(Request.objects.only("items").get(), request)
+
+    def test_defer_reverse_many_to_many_ignored(self):
+        location = Location.objects.create()
+        request = Request.objects.create(location=location)
+        item = Item.objects.create(value=1)
+        request.items.add(item)
+        with self.assertNumQueries(1):
+            self.assertEqual(Item.objects.defer("request").get(), item)
+
+    def test_only_reverse_many_to_many_ignored(self):
+        location = Location.objects.create()
+        request = Request.objects.create(location=location)
+        item = Item.objects.create(value=1)
+        request.items.add(item)
+        with self.assertNumQueries(1):
+            self.assertEqual(Item.objects.only("request").get(), item)
+
 
 class DeferDeletionSignalsTests(TestCase):
     senders = [Item, Proxy]
diff --git a/tests/select_related_onetoone/tests.py b/tests/select_related_onetoone/tests.py
--- a/tests/select_related_onetoone/tests.py
+++ b/tests/select_related_onetoone/tests.py
@@ -249,6 +249,9 @@ def test_inheritance_deferred2(self):
             self.assertEqual(p.child1.name2, "n2")
         p = qs.get(name2="n2")
         with self.assertNumQueries(0):
+            self.assertEqual(p.child1.value, 1)
+            self.assertEqual(p.child1.child4.value4, 4)
+        with self.assertNumQueries(2):
             self.assertEqual(p.child1.name1, "n1")
             self.assertEqual(p.child1.child4.name1, "n1")
 

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 defer_regress.tests select_related_onetoone.tests
: '>>>>> End Test Output'
git checkout 4142739af1cda53581af4169dbe16d6cd5e26948 tests/defer_regress/tests.py tests/select_related_onetoone/tests.py
