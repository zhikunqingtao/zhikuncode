#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff c61219a7ae051d2baab53f041e00592011fc550c
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout c61219a7ae051d2baab53f041e00592011fc550c tests/migrations/test_autodetector.py tests/migrations/test_optimizer.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/migrations/test_autodetector.py b/tests/migrations/test_autodetector.py
--- a/tests/migrations/test_autodetector.py
+++ b/tests/migrations/test_autodetector.py
@@ -2266,10 +2266,9 @@ def test_same_app_circular_fk_dependency_with_unique_together_and_indexes(self):
             changes,
             "eggs",
             0,
-            ["CreateModel", "CreateModel", "AddIndex", "AlterUniqueTogether"],
+            ["CreateModel", "CreateModel"],
         )
         self.assertNotIn("unique_together", changes["eggs"][0].operations[0].options)
-        self.assertNotIn("unique_together", changes["eggs"][0].operations[1].options)
         self.assertMigrationDependencies(changes, "eggs", 0, [])
 
     def test_alter_db_table_add(self):
@@ -2565,6 +2564,9 @@ def test(from_state, to_state, msg):
 
     def test_create_model_with_indexes(self):
         """Test creation of new model with indexes already defined."""
+        added_index = models.Index(
+            fields=["name"], name="create_model_with_indexes_idx"
+        )
         author = ModelState(
             "otherapp",
             "Author",
@@ -2573,25 +2575,25 @@ def test_create_model_with_indexes(self):
                 ("name", models.CharField(max_length=200)),
             ],
             {
-                "indexes": [
-                    models.Index(fields=["name"], name="create_model_with_indexes_idx")
-                ]
+                "indexes": [added_index],
             },
         )
         changes = self.get_changes([], [author])
-        added_index = models.Index(
-            fields=["name"], name="create_model_with_indexes_idx"
-        )
         # Right number of migrations?
         self.assertEqual(len(changes["otherapp"]), 1)
         # Right number of actions?
         migration = changes["otherapp"][0]
-        self.assertEqual(len(migration.operations), 2)
+        self.assertEqual(len(migration.operations), 1)
         # Right actions order?
-        self.assertOperationTypes(changes, "otherapp", 0, ["CreateModel", "AddIndex"])
+        self.assertOperationTypes(changes, "otherapp", 0, ["CreateModel"])
         self.assertOperationAttributes(changes, "otherapp", 0, 0, name="Author")
         self.assertOperationAttributes(
-            changes, "otherapp", 0, 1, model_name="author", index=added_index
+            changes,
+            "otherapp",
+            0,
+            0,
+            name="Author",
+            options={"indexes": [added_index]},
         )
 
     def test_add_indexes(self):
@@ -4043,62 +4045,69 @@ def test_add_model_order_with_respect_to_unique_together(self):
             },
         )
 
-    def test_add_model_order_with_respect_to_index_constraint(self):
-        tests = [
-            (
-                "AddIndex",
-                {
-                    "indexes": [
-                        models.Index(fields=["_order"], name="book_order_idx"),
-                    ]
-                },
-            ),
-            (
-                "AddConstraint",
-                {
-                    "constraints": [
-                        models.CheckConstraint(
-                            check=models.Q(_order__gt=1),
-                            name="book_order_gt_1",
-                        ),
-                    ]
-                },
-            ),
-        ]
-        for operation, extra_option in tests:
-            with self.subTest(operation=operation):
-                after = ModelState(
-                    "testapp",
-                    "Author",
-                    [
-                        ("id", models.AutoField(primary_key=True)),
-                        ("name", models.CharField(max_length=200)),
-                        ("book", models.ForeignKey("otherapp.Book", models.CASCADE)),
-                    ],
-                    options={
-                        "order_with_respect_to": "book",
-                        **extra_option,
-                    },
-                )
-                changes = self.get_changes([], [self.book, after])
-                self.assertNumberMigrations(changes, "testapp", 1)
-                self.assertOperationTypes(
-                    changes,
-                    "testapp",
-                    0,
-                    [
-                        "CreateModel",
-                        operation,
-                    ],
-                )
-                self.assertOperationAttributes(
-                    changes,
-                    "testapp",
-                    0,
-                    0,
-                    name="Author",
-                    options={"order_with_respect_to": "book"},
-                )
+    def test_add_model_order_with_respect_to_constraint(self):
+        after = ModelState(
+            "testapp",
+            "Author",
+            [
+                ("id", models.AutoField(primary_key=True)),
+                ("name", models.CharField(max_length=200)),
+                ("book", models.ForeignKey("otherapp.Book", models.CASCADE)),
+            ],
+            options={
+                "order_with_respect_to": "book",
+                "constraints": [
+                    models.CheckConstraint(
+                        check=models.Q(_order__gt=1), name="book_order_gt_1"
+                    ),
+                ],
+            },
+        )
+        changes = self.get_changes([], [self.book, after])
+        self.assertNumberMigrations(changes, "testapp", 1)
+        self.assertOperationTypes(
+            changes,
+            "testapp",
+            0,
+            ["CreateModel", "AddConstraint"],
+        )
+        self.assertOperationAttributes(
+            changes,
+            "testapp",
+            0,
+            0,
+            name="Author",
+            options={"order_with_respect_to": "book"},
+        )
+
+    def test_add_model_order_with_respect_to_index(self):
+        after = ModelState(
+            "testapp",
+            "Author",
+            [
+                ("id", models.AutoField(primary_key=True)),
+                ("name", models.CharField(max_length=200)),
+                ("book", models.ForeignKey("otherapp.Book", models.CASCADE)),
+            ],
+            options={
+                "order_with_respect_to": "book",
+                "indexes": [models.Index(fields=["_order"], name="book_order_idx")],
+            },
+        )
+        changes = self.get_changes([], [self.book, after])
+        self.assertNumberMigrations(changes, "testapp", 1)
+        self.assertOperationTypes(changes, "testapp", 0, ["CreateModel"])
+        self.assertOperationAttributes(
+            changes,
+            "testapp",
+            0,
+            0,
+            name="Author",
+            options={
+                "order_with_respect_to": "book",
+                "indexes": [models.Index(fields=["_order"], name="book_order_idx")],
+            },
+        )
 
     def test_set_alter_order_with_respect_to_index_constraint_unique_together(self):
         tests = [
diff --git a/tests/migrations/test_optimizer.py b/tests/migrations/test_optimizer.py
--- a/tests/migrations/test_optimizer.py
+++ b/tests/migrations/test_optimizer.py
@@ -1172,3 +1172,181 @@ def test_add_remove_index(self):
             ],
             [],
         )
+
+    def test_create_model_add_index(self):
+        self.assertOptimizesTo(
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                    ],
+                    options={
+                        "indexes": [models.Index(fields=["age"], name="idx_pony_age")],
+                    },
+                ),
+                migrations.AddIndex(
+                    "Pony",
+                    models.Index(fields=["weight"], name="idx_pony_weight"),
+                ),
+            ],
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                    ],
+                    options={
+                        "indexes": [
+                            models.Index(fields=["age"], name="idx_pony_age"),
+                            models.Index(fields=["weight"], name="idx_pony_weight"),
+                        ],
+                    },
+                ),
+            ],
+        )
+
+    def test_create_model_remove_index(self):
+        self.assertOptimizesTo(
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                    ],
+                    options={
+                        "indexes": [
+                            models.Index(fields=["age"], name="idx_pony_age"),
+                            models.Index(fields=["weight"], name="idx_pony_weight"),
+                        ],
+                    },
+                ),
+                migrations.RemoveIndex("Pony", "idx_pony_age"),
+            ],
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                    ],
+                    options={
+                        "indexes": [
+                            models.Index(fields=["weight"], name="idx_pony_weight"),
+                        ],
+                    },
+                ),
+            ],
+        )
+
+    def test_create_model_remove_index_together_rename_index(self):
+        self.assertOptimizesTo(
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                    ],
+                    options={
+                        "index_together": [("age", "weight")],
+                    },
+                ),
+                migrations.RenameIndex(
+                    "Pony", new_name="idx_pony_age_weight", old_fields=("age", "weight")
+                ),
+            ],
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                    ],
+                    options={
+                        "indexes": [
+                            models.Index(
+                                fields=["age", "weight"], name="idx_pony_age_weight"
+                            ),
+                        ],
+                    },
+                ),
+            ],
+        )
+
+    def test_create_model_index_together_rename_index(self):
+        self.assertOptimizesTo(
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                        ("height", models.IntegerField()),
+                        ("rank", models.IntegerField()),
+                    ],
+                    options={
+                        "index_together": [("age", "weight"), ("height", "rank")],
+                    },
+                ),
+                migrations.RenameIndex(
+                    "Pony", new_name="idx_pony_age_weight", old_fields=("age", "weight")
+                ),
+            ],
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                        ("height", models.IntegerField()),
+                        ("rank", models.IntegerField()),
+                    ],
+                    options={
+                        "index_together": {("height", "rank")},
+                        "indexes": [
+                            models.Index(
+                                fields=["age", "weight"], name="idx_pony_age_weight"
+                            ),
+                        ],
+                    },
+                ),
+            ],
+        )
+
+    def test_create_model_rename_index_no_old_fields(self):
+        self.assertOptimizesTo(
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                    ],
+                    options={
+                        "indexes": [models.Index(fields=["age"], name="idx_pony_age")],
+                    },
+                ),
+                migrations.RenameIndex(
+                    "Pony", new_name="idx_pony_age_new", old_name="idx_pony_age"
+                ),
+            ],
+            [
+                migrations.CreateModel(
+                    name="Pony",
+                    fields=[
+                        ("weight", models.IntegerField()),
+                        ("age", models.IntegerField()),
+                    ],
+                    options={
+                        "indexes": [models.Index(fields=["age"], name="idx_pony_age")],
+                    },
+                ),
+                migrations.RenameIndex(
+                    "Pony", new_name="idx_pony_age_new", old_name="idx_pony_age"
+                ),
+            ],
+        )

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 migrations.test_autodetector migrations.test_optimizer
: '>>>>> End Test Output'
git checkout c61219a7ae051d2baab53f041e00592011fc550c tests/migrations/test_autodetector.py tests/migrations/test_optimizer.py
