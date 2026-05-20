#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 182ce3dd15dfa3537391c3efaf9c3ff407d134d4
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout 182ce3dd15dfa3537391c3efaf9c3ff407d134d4 tests/test_cli.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/test_cli.py b/tests/test_cli.py
--- a/tests/test_cli.py
+++ b/tests/test_cli.py
@@ -433,16 +433,12 @@ class TestRoutes:
     @pytest.fixture
     def app(self):
         app = Flask(__name__)
-        app.testing = True
-
-        @app.route("/get_post/<int:x>/<int:y>", methods=["GET", "POST"])
-        def yyy_get_post(x, y):
-            pass
-
-        @app.route("/zzz_post", methods=["POST"])
-        def aaa_post():
-            pass
-
+        app.add_url_rule(
+            "/get_post/<int:x>/<int:y>",
+            methods=["GET", "POST"],
+            endpoint="yyy_get_post",
+        )
+        app.add_url_rule("/zzz_post", methods=["POST"], endpoint="aaa_post")
         return app
 
     @pytest.fixture
@@ -450,17 +446,6 @@ def invoke(self, app, runner):
         cli = FlaskGroup(create_app=lambda: app)
         return partial(runner.invoke, cli)
 
-    @pytest.fixture
-    def invoke_no_routes(self, runner):
-        def create_app():
-            app = Flask(__name__, static_folder=None)
-            app.testing = True
-
-            return app
-
-        cli = FlaskGroup(create_app=create_app)
-        return partial(runner.invoke, cli)
-
     def expect_order(self, order, output):
         # skip the header and match the start of each row
         for expect, line in zip(order, output.splitlines()[2:]):
@@ -493,11 +478,31 @@ def test_all_methods(self, invoke):
         output = invoke(["routes", "--all-methods"]).output
         assert "GET, HEAD, OPTIONS, POST" in output
 
-    def test_no_routes(self, invoke_no_routes):
-        result = invoke_no_routes(["routes"])
+    def test_no_routes(self, runner):
+        app = Flask(__name__, static_folder=None)
+        cli = FlaskGroup(create_app=lambda: app)
+        result = runner.invoke(cli, ["routes"])
         assert result.exit_code == 0
         assert "No routes were registered." in result.output
 
+    def test_subdomain(self, runner):
+        app = Flask(__name__, static_folder=None)
+        app.add_url_rule("/a", subdomain="a", endpoint="a")
+        app.add_url_rule("/b", subdomain="b", endpoint="b")
+        cli = FlaskGroup(create_app=lambda: app)
+        result = runner.invoke(cli, ["routes"])
+        assert result.exit_code == 0
+        assert "Subdomain" in result.output
+
+    def test_host(self, runner):
+        app = Flask(__name__, static_folder=None, host_matching=True)
+        app.add_url_rule("/a", host="a", endpoint="a")
+        app.add_url_rule("/b", host="b", endpoint="b")
+        cli = FlaskGroup(create_app=lambda: app)
+        result = runner.invoke(cli, ["routes"])
+        assert result.exit_code == 0
+        assert "Host" in result.output
+
 
 def dotenv_not_available():
     try:

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA tests/test_cli.py
: '>>>>> End Test Output'
git checkout 182ce3dd15dfa3537391c3efaf9c3ff407d134d4 tests/test_cli.py
