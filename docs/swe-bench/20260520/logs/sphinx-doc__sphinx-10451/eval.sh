#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 195e911f1dab04b8ddeacbe04b7d214aaf81bb0b
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .[test]
git checkout 195e911f1dab04b8ddeacbe04b7d214aaf81bb0b tests/roots/test-ext-autodoc/target/typehints.py tests/test_ext_autodoc_configs.py tests/test_ext_napoleon_docstring.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/roots/test-ext-autodoc/target/typehints.py b/tests/roots/test-ext-autodoc/target/typehints.py
--- a/tests/roots/test-ext-autodoc/target/typehints.py
+++ b/tests/roots/test-ext-autodoc/target/typehints.py
@@ -94,8 +94,10 @@ def missing_attr(c,
 class _ClassWithDocumentedInit:
     """Class docstring."""
 
-    def __init__(self, x: int) -> None:
+    def __init__(self, x: int, *args: int, **kwargs: int) -> None:
         """Init docstring.
 
         :param x: Some integer
+        :param args: Some integer
+        :param kwargs: Some integer
         """
diff --git a/tests/roots/test-ext-napoleon/conf.py b/tests/roots/test-ext-napoleon/conf.py
new file mode 100644
--- /dev/null
+++ b/tests/roots/test-ext-napoleon/conf.py
@@ -0,0 +1,5 @@
+import os
+import sys
+
+sys.path.insert(0, os.path.abspath('.'))
+extensions = ['sphinx.ext.napoleon']
diff --git a/tests/roots/test-ext-napoleon/index.rst b/tests/roots/test-ext-napoleon/index.rst
new file mode 100644
--- /dev/null
+++ b/tests/roots/test-ext-napoleon/index.rst
@@ -0,0 +1,6 @@
+test-ext-napoleon
+=================
+
+.. toctree::
+
+   typehints
diff --git a/tests/roots/test-ext-napoleon/mypackage/__init__.py b/tests/roots/test-ext-napoleon/mypackage/__init__.py
new file mode 100644
diff --git a/tests/roots/test-ext-napoleon/mypackage/typehints.py b/tests/roots/test-ext-napoleon/mypackage/typehints.py
new file mode 100644
--- /dev/null
+++ b/tests/roots/test-ext-napoleon/mypackage/typehints.py
@@ -0,0 +1,11 @@
+def hello(x: int, *args: int, **kwargs: int) -> None:
+    """
+    Parameters
+    ----------
+    x
+        X
+    *args
+        Additional arguments.
+    **kwargs
+        Extra arguments.
+    """
diff --git a/tests/roots/test-ext-napoleon/typehints.rst b/tests/roots/test-ext-napoleon/typehints.rst
new file mode 100644
--- /dev/null
+++ b/tests/roots/test-ext-napoleon/typehints.rst
@@ -0,0 +1,5 @@
+typehints
+=========
+
+.. automodule:: mypackage.typehints
+   :members:
diff --git a/tests/test_ext_autodoc_configs.py b/tests/test_ext_autodoc_configs.py
--- a/tests/test_ext_autodoc_configs.py
+++ b/tests/test_ext_autodoc_configs.py
@@ -1034,19 +1034,27 @@ def test_autodoc_typehints_description_with_documented_init(app):
     )
     app.build()
     context = (app.outdir / 'index.txt').read_text(encoding='utf8')
-    assert ('class target.typehints._ClassWithDocumentedInit(x)\n'
+    assert ('class target.typehints._ClassWithDocumentedInit(x, *args, **kwargs)\n'
             '\n'
             '   Class docstring.\n'
             '\n'
             '   Parameters:\n'
-            '      **x** (*int*) --\n'
+            '      * **x** (*int*) --\n'
             '\n'
-            '   __init__(x)\n'
+            '      * **args** (*int*) --\n'
+            '\n'
+            '      * **kwargs** (*int*) --\n'
+            '\n'
+            '   __init__(x, *args, **kwargs)\n'
             '\n'
             '      Init docstring.\n'
             '\n'
             '      Parameters:\n'
-            '         **x** (*int*) -- Some integer\n'
+            '         * **x** (*int*) -- Some integer\n'
+            '\n'
+            '         * **args** (*int*) -- Some integer\n'
+            '\n'
+            '         * **kwargs** (*int*) -- Some integer\n'
             '\n'
             '      Return type:\n'
             '         None\n' == context)
@@ -1063,16 +1071,20 @@ def test_autodoc_typehints_description_with_documented_init_no_undoc(app):
     )
     app.build()
     context = (app.outdir / 'index.txt').read_text(encoding='utf8')
-    assert ('class target.typehints._ClassWithDocumentedInit(x)\n'
+    assert ('class target.typehints._ClassWithDocumentedInit(x, *args, **kwargs)\n'
             '\n'
             '   Class docstring.\n'
             '\n'
-            '   __init__(x)\n'
+            '   __init__(x, *args, **kwargs)\n'
             '\n'
             '      Init docstring.\n'
             '\n'
             '      Parameters:\n'
-            '         **x** (*int*) -- Some integer\n' == context)
+            '         * **x** (*int*) -- Some integer\n'
+            '\n'
+            '         * **args** (*int*) -- Some integer\n'
+            '\n'
+            '         * **kwargs** (*int*) -- Some integer\n' == context)
 
 
 @pytest.mark.sphinx('text', testroot='ext-autodoc',
@@ -1089,16 +1101,20 @@ def test_autodoc_typehints_description_with_documented_init_no_undoc_doc_rtype(a
     )
     app.build()
     context = (app.outdir / 'index.txt').read_text(encoding='utf8')
-    assert ('class target.typehints._ClassWithDocumentedInit(x)\n'
+    assert ('class target.typehints._ClassWithDocumentedInit(x, *args, **kwargs)\n'
             '\n'
             '   Class docstring.\n'
             '\n'
-            '   __init__(x)\n'
+            '   __init__(x, *args, **kwargs)\n'
             '\n'
             '      Init docstring.\n'
             '\n'
             '      Parameters:\n'
-            '         **x** (*int*) -- Some integer\n' == context)
+            '         * **x** (*int*) -- Some integer\n'
+            '\n'
+            '         * **args** (*int*) -- Some integer\n'
+            '\n'
+            '         * **kwargs** (*int*) -- Some integer\n' == context)
 
 
 @pytest.mark.sphinx('text', testroot='ext-autodoc',
diff --git a/tests/test_ext_napoleon_docstring.py b/tests/test_ext_napoleon_docstring.py
--- a/tests/test_ext_napoleon_docstring.py
+++ b/tests/test_ext_napoleon_docstring.py
@@ -2593,3 +2593,48 @@ def test_pep526_annotations(self):
 """
         print(actual)
         assert expected == actual
+
+
+@pytest.mark.sphinx('text', testroot='ext-napoleon',
+                    confoverrides={'autodoc_typehints': 'description',
+                                   'autodoc_typehints_description_target': 'all'})
+def test_napoleon_and_autodoc_typehints_description_all(app, status, warning):
+    app.build()
+    content = (app.outdir / 'typehints.txt').read_text(encoding='utf-8')
+    assert content == (
+        'typehints\n'
+        '*********\n'
+        '\n'
+        'mypackage.typehints.hello(x, *args, **kwargs)\n'
+        '\n'
+        '   Parameters:\n'
+        '      * **x** (*int*) -- X\n'
+        '\n'
+        '      * ***args** (*int*) -- Additional arguments.\n'
+        '\n'
+        '      * ****kwargs** (*int*) -- Extra arguments.\n'
+        '\n'
+        '   Return type:\n'
+        '      None\n'
+    )
+
+
+@pytest.mark.sphinx('text', testroot='ext-napoleon',
+                    confoverrides={'autodoc_typehints': 'description',
+                                   'autodoc_typehints_description_target': 'documented_params'})
+def test_napoleon_and_autodoc_typehints_description_documented_params(app, status, warning):
+    app.build()
+    content = (app.outdir / 'typehints.txt').read_text(encoding='utf-8')
+    assert content == (
+        'typehints\n'
+        '*********\n'
+        '\n'
+        'mypackage.typehints.hello(x, *args, **kwargs)\n'
+        '\n'
+        '   Parameters:\n'
+        '      * **x** (*int*) -- X\n'
+        '\n'
+        '      * ***args** (*int*) -- Additional arguments.\n'
+        '\n'
+        '      * ****kwargs** (*int*) -- Extra arguments.\n'
+    )

EOF_114329324912
: '>>>>> Start Test Output'
tox --current-env -epy39 -v -- tests/roots/test-ext-autodoc/target/typehints.py tests/roots/test-ext-napoleon/conf.py tests/roots/test-ext-napoleon/index.rst tests/roots/test-ext-napoleon/mypackage/__init__.py tests/roots/test-ext-napoleon/mypackage/typehints.py tests/roots/test-ext-napoleon/typehints.rst tests/test_ext_autodoc_configs.py tests/test_ext_napoleon_docstring.py
: '>>>>> End Test Output'
git checkout 195e911f1dab04b8ddeacbe04b7d214aaf81bb0b tests/roots/test-ext-autodoc/target/typehints.py tests/test_ext_autodoc_configs.py tests/test_ext_napoleon_docstring.py
