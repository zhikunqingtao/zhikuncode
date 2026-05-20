#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 4c288bc97ea371817199908d0d9b12de9dae327e
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 4c288bc97ea371817199908d0d9b12de9dae327e tests/test_config.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/static/config.toml b/tests/static/config.toml
new file mode 100644
--- /dev/null
+++ b/tests/static/config.toml
@@ -0,0 +1,2 @@
+TEST_KEY="foo"
+SECRET_KEY="config"
diff --git a/tests/test_config.py b/tests/test_config.py
--- a/tests/test_config.py
+++ b/tests/test_config.py
@@ -6,7 +6,6 @@
 
 import flask
 
-
 # config keys used for the TestConfig
 TEST_KEY = "foo"
 SECRET_KEY = "config"
@@ -30,13 +29,23 @@ def test_config_from_object():
     common_object_test(app)
 
 
-def test_config_from_file():
+def test_config_from_file_json():
     app = flask.Flask(__name__)
     current_dir = os.path.dirname(os.path.abspath(__file__))
     app.config.from_file(os.path.join(current_dir, "static", "config.json"), json.load)
     common_object_test(app)
 
 
+def test_config_from_file_toml():
+    tomllib = pytest.importorskip("tomllib", reason="tomllib added in 3.11")
+    app = flask.Flask(__name__)
+    current_dir = os.path.dirname(os.path.abspath(__file__))
+    app.config.from_file(
+        os.path.join(current_dir, "static", "config.toml"), tomllib.load, text=False
+    )
+    common_object_test(app)
+
+
 def test_from_prefixed_env(monkeypatch):
     monkeypatch.setenv("FLASK_STRING", "value")
     monkeypatch.setenv("FLASK_BOOL", "true")

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA tests/test_config.py
: '>>>>> End Test Output'
git checkout 4c288bc97ea371817199908d0d9b12de9dae327e tests/test_config.py
