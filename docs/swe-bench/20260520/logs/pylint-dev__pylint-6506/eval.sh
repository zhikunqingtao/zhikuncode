#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 0a4204fd7555cfedd43f43017c94d24ef48244a5
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 0a4204fd7555cfedd43f43017c94d24ef48244a5 tests/config/test_config.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/config/test_config.py b/tests/config/test_config.py
--- a/tests/config/test_config.py
+++ b/tests/config/test_config.py
@@ -10,7 +10,6 @@
 import pytest
 from pytest import CaptureFixture
 
-from pylint.config.exceptions import _UnrecognizedOptionError
 from pylint.lint import Run as LintRun
 from pylint.testutils._run import _Run as Run
 from pylint.testutils.configuration_test import run_using_a_configuration_file
@@ -65,18 +64,20 @@ def test_unknown_message_id(capsys: CaptureFixture) -> None:
 
 def test_unknown_option_name(capsys: CaptureFixture) -> None:
     """Check that we correctly raise a message on an unknown option."""
-    with pytest.raises(_UnrecognizedOptionError):
+    with pytest.raises(SystemExit):
         Run([str(EMPTY_MODULE), "--unknown-option=yes"], exit=False)
     output = capsys.readouterr()
-    assert "E0015: Unrecognized option found: unknown-option=yes" in output.out
+    assert "usage: pylint" in output.err
+    assert "Unrecognized option" in output.err
 
 
 def test_unknown_short_option_name(capsys: CaptureFixture) -> None:
     """Check that we correctly raise a message on an unknown short option."""
-    with pytest.raises(_UnrecognizedOptionError):
+    with pytest.raises(SystemExit):
         Run([str(EMPTY_MODULE), "-Q"], exit=False)
     output = capsys.readouterr()
-    assert "E0015: Unrecognized option found: Q" in output.out
+    assert "usage: pylint" in output.err
+    assert "Unrecognized option" in output.err
 
 
 def test_unknown_confidence(capsys: CaptureFixture) -> None:

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA tests/config/test_config.py
: '>>>>> End Test Output'
git checkout 0a4204fd7555cfedd43f43017c94d24ef48244a5 tests/config/test_config.py
