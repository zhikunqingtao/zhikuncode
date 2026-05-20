#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 10ca84ffc56c2dd2d9dc4bd71b7b898e083500cd
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 10ca84ffc56c2dd2d9dc4bd71b7b898e083500cd testing/test_assertrewrite.py
git apply -v - <<'EOF_114329324912'
diff --git a/testing/test_assertrewrite.py b/testing/test_assertrewrite.py
--- a/testing/test_assertrewrite.py
+++ b/testing/test_assertrewrite.py
@@ -656,6 +656,12 @@ def __repr__(self):
         else:
             assert lines == ["assert 0 == 1\n +  where 1 = \\n{ \\n~ \\n}.a"]
 
+    def test_unroll_expression(self):
+        def f():
+            assert all(x == 1 for x in range(10))
+
+        assert "0 == 1" in getmsg(f)
+
     def test_custom_repr_non_ascii(self):
         def f():
             class A(object):
@@ -671,6 +677,53 @@ def __repr__(self):
         assert "UnicodeDecodeError" not in msg
         assert "UnicodeEncodeError" not in msg
 
+    def test_unroll_generator(self, testdir):
+        testdir.makepyfile(
+            """
+            def check_even(num):
+                if num % 2 == 0:
+                    return True
+                return False
+
+            def test_generator():
+                odd_list = list(range(1,9,2))
+                assert all(check_even(num) for num in odd_list)"""
+        )
+        result = testdir.runpytest()
+        result.stdout.fnmatch_lines(["*assert False*", "*where False = check_even(1)*"])
+
+    def test_unroll_list_comprehension(self, testdir):
+        testdir.makepyfile(
+            """
+            def check_even(num):
+                if num % 2 == 0:
+                    return True
+                return False
+
+            def test_list_comprehension():
+                odd_list = list(range(1,9,2))
+                assert all([check_even(num) for num in odd_list])"""
+        )
+        result = testdir.runpytest()
+        result.stdout.fnmatch_lines(["*assert False*", "*where False = check_even(1)*"])
+
+    def test_for_loop(self, testdir):
+        testdir.makepyfile(
+            """
+            def check_even(num):
+                if num % 2 == 0:
+                    return True
+                return False
+
+            def test_for_loop():
+                odd_list = list(range(1,9,2))
+                for num in odd_list:
+                    assert check_even(num)
+        """
+        )
+        result = testdir.runpytest()
+        result.stdout.fnmatch_lines(["*assert False*", "*where False = check_even(1)*"])
+
 
 class TestRewriteOnImport(object):
     def test_pycache_is_a_file(self, testdir):

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA testing/test_assertrewrite.py
: '>>>>> End Test Output'
git checkout 10ca84ffc56c2dd2d9dc4bd71b7b898e083500cd testing/test_assertrewrite.py
