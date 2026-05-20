#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff ec13e801b820614ff374cb0046092caab8d67249
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout ec13e801b820614ff374cb0046092caab8d67249 tests/utils_tests/test_numberformat.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/utils_tests/test_numberformat.py b/tests/utils_tests/test_numberformat.py
--- a/tests/utils_tests/test_numberformat.py
+++ b/tests/utils_tests/test_numberformat.py
@@ -172,3 +172,7 @@ def __format__(self, specifier, **kwargs):
 
         price = EuroDecimal("1.23")
         self.assertEqual(nformat(price, ","), "€ 1,23")
+
+    def test_empty(self):
+        self.assertEqual(nformat("", "."), "")
+        self.assertEqual(nformat(None, "."), "None")

EOF_114329324912
: '>>>>> Start Test Output'
./tests/runtests.py --verbosity 2 --settings=test_sqlite --parallel 1 utils_tests.test_numberformat
: '>>>>> End Test Output'
git checkout ec13e801b820614ff374cb0046092caab8d67249 tests/utils_tests/test_numberformat.py
