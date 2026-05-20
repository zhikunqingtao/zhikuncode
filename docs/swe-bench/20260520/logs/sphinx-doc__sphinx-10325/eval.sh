#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 7bdc11e87c7d86dcc2a087eccb7a7c129a473415
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .[test]
git checkout 7bdc11e87c7d86dcc2a087eccb7a7c129a473415 tests/roots/test-ext-autodoc/target/inheritance.py tests/test_ext_autodoc_automodule.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/roots/test-ext-autodoc/target/inheritance.py b/tests/roots/test-ext-autodoc/target/inheritance.py
--- a/tests/roots/test-ext-autodoc/target/inheritance.py
+++ b/tests/roots/test-ext-autodoc/target/inheritance.py
@@ -15,3 +15,8 @@ class Derived(Base):
     def inheritedmeth(self):
         # no docstring here
         pass
+
+
+class MyList(list):
+    def meth(self):
+        """docstring"""
diff --git a/tests/test_ext_autodoc_automodule.py b/tests/test_ext_autodoc_automodule.py
--- a/tests/test_ext_autodoc_automodule.py
+++ b/tests/test_ext_autodoc_automodule.py
@@ -113,6 +113,68 @@ def test_automodule_special_members(app):
     ]
 
 
+@pytest.mark.sphinx('html', testroot='ext-autodoc')
+def test_automodule_inherited_members(app):
+    if sys.version_info < (3, 7):
+        args = ''
+    else:
+        args = '(iterable=(), /)'
+
+    options = {'members': None,
+               'undoc-members': None,
+               'inherited-members': 'Base, list'}
+    actual = do_autodoc(app, 'module', 'target.inheritance', options)
+    assert list(actual) == [
+        '',
+        '.. py:module:: target.inheritance',
+        '',
+        '',
+        '.. py:class:: Base()',
+        '   :module: target.inheritance',
+        '',
+        '',
+        '   .. py:method:: Base.inheritedclassmeth()',
+        '      :module: target.inheritance',
+        '      :classmethod:',
+        '',
+        '      Inherited class method.',
+        '',
+        '',
+        '   .. py:method:: Base.inheritedmeth()',
+        '      :module: target.inheritance',
+        '',
+        '      Inherited function.',
+        '',
+        '',
+        '   .. py:method:: Base.inheritedstaticmeth(cls)',
+        '      :module: target.inheritance',
+        '      :staticmethod:',
+        '',
+        '      Inherited static method.',
+        '',
+        '',
+        '.. py:class:: Derived()',
+        '   :module: target.inheritance',
+        '',
+        '',
+        '   .. py:method:: Derived.inheritedmeth()',
+        '      :module: target.inheritance',
+        '',
+        '      Inherited function.',
+        '',
+        '',
+        '.. py:class:: MyList%s' % args,
+        '   :module: target.inheritance',
+        '',
+        '',
+        '   .. py:method:: MyList.meth()',
+        '      :module: target.inheritance',
+        '',
+        '      docstring',
+        '',
+    ]
+
+
 @pytest.mark.sphinx('html', testroot='ext-autodoc',
                     confoverrides={'autodoc_mock_imports': ['missing_module',
                                                             'missing_package1',

EOF_114329324912
: '>>>>> Start Test Output'
tox --current-env -epy39 -v -- tests/roots/test-ext-autodoc/target/inheritance.py tests/test_ext_autodoc_automodule.py
: '>>>>> End Test Output'
git checkout 7bdc11e87c7d86dcc2a087eccb7a7c129a473415 tests/roots/test-ext-autodoc/target/inheritance.py tests/test_ext_autodoc_automodule.py
