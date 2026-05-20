#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 2051e30b9b596e944524ccb787ed20f9f5be93e3
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 2051e30b9b596e944524ccb787ed20f9f5be93e3 testing/logging/test_reporting.py
git apply -v - <<'EOF_114329324912'
diff --git a/testing/logging/test_reporting.py b/testing/logging/test_reporting.py
--- a/testing/logging/test_reporting.py
+++ b/testing/logging/test_reporting.py
@@ -248,7 +248,7 @@ def test_log_cli():
             [
                 "test_log_cli_enabled_disabled.py::test_log_cli ",
                 "*-- live log call --*",
-                "test_log_cli_enabled_disabled.py* CRITICAL critical message logged by test",
+                "CRITICAL *test_log_cli_enabled_disabled.py* critical message logged by test",
                 "PASSED*",
             ]
         )
@@ -282,7 +282,7 @@ def test_log_cli(request):
     result.stdout.fnmatch_lines(
         [
             "test_log_cli_default_level.py::test_log_cli ",
-            "test_log_cli_default_level.py*WARNING message will be shown*",
+            "WARNING*test_log_cli_default_level.py* message will be shown*",
         ]
     )
     assert "INFO message won't be shown" not in result.stdout.str()
@@ -523,7 +523,7 @@ def test_log_1(fix):
     )
     assert (
         re.search(
-            r"(.+)live log teardown(.+)\n(.+)WARNING(.+)\n(.+)WARNING(.+)",
+            r"(.+)live log teardown(.+)\nWARNING(.+)\nWARNING(.+)",
             result.stdout.str(),
             re.MULTILINE,
         )
@@ -531,7 +531,7 @@ def test_log_1(fix):
     )
     assert (
         re.search(
-            r"(.+)live log finish(.+)\n(.+)WARNING(.+)\n(.+)WARNING(.+)",
+            r"(.+)live log finish(.+)\nWARNING(.+)\nWARNING(.+)",
             result.stdout.str(),
             re.MULTILINE,
         )
@@ -565,7 +565,7 @@ def test_log_cli(request):
     # fnmatch_lines does an assertion internally
     result.stdout.fnmatch_lines(
         [
-            "test_log_cli_level.py*This log message will be shown",
+            "*test_log_cli_level.py*This log message will be shown",
             "PASSED",  # 'PASSED' on its own line because the log message prints a new line
         ]
     )
@@ -579,7 +579,7 @@ def test_log_cli(request):
     # fnmatch_lines does an assertion internally
     result.stdout.fnmatch_lines(
         [
-            "test_log_cli_level.py* This log message will be shown",
+            "*test_log_cli_level.py* This log message will be shown",
             "PASSED",  # 'PASSED' on its own line because the log message prints a new line
         ]
     )
@@ -615,7 +615,7 @@ def test_log_cli(request):
     # fnmatch_lines does an assertion internally
     result.stdout.fnmatch_lines(
         [
-            "test_log_cli_ini_level.py* This log message will be shown",
+            "*test_log_cli_ini_level.py* This log message will be shown",
             "PASSED",  # 'PASSED' on its own line because the log message prints a new line
         ]
     )

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA testing/logging/test_reporting.py
: '>>>>> End Test Output'
git checkout 2051e30b9b596e944524ccb787ed20f9f5be93e3 testing/logging/test_reporting.py
