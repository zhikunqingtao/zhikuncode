#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 3ed7590ed411bd93b26098faab4f23619cdb2267
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .[test]
git checkout 3ed7590ed411bd93b26098faab4f23619cdb2267 tests/test_ext_napoleon_docstring.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/test_ext_napoleon_docstring.py b/tests/test_ext_napoleon_docstring.py
--- a/tests/test_ext_napoleon_docstring.py
+++ b/tests/test_ext_napoleon_docstring.py
@@ -1441,12 +1441,18 @@ def test_parameters_with_class_reference(self):
 ----------
 param1 : :class:`MyClass <name.space.MyClass>` instance
 
+Other Parameters
+----------------
+param2 : :class:`MyClass <name.space.MyClass>` instance
+
 """
 
         config = Config(napoleon_use_param=False)
         actual = str(NumpyDocstring(docstring, config))
         expected = """\
 :Parameters: **param1** (:class:`MyClass <name.space.MyClass>` instance)
+
+:Other Parameters: **param2** (:class:`MyClass <name.space.MyClass>` instance)
 """
         self.assertEqual(expected, actual)
 
@@ -1455,6 +1461,9 @@ def test_parameters_with_class_reference(self):
         expected = """\
 :param param1:
 :type param1: :class:`MyClass <name.space.MyClass>` instance
+
+:param param2:
+:type param2: :class:`MyClass <name.space.MyClass>` instance
 """
         self.assertEqual(expected, actual)
 

EOF_114329324912
: '>>>>> Start Test Output'
tox --current-env -epy39 -v -- tests/test_ext_napoleon_docstring.py
: '>>>>> End Test Output'
git checkout 3ed7590ed411bd93b26098faab4f23619cdb2267 tests/test_ext_napoleon_docstring.py
