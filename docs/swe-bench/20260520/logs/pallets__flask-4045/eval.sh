#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff d8c37f43724cd9fb0870f77877b7c4c7e38a19e0
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .
git checkout d8c37f43724cd9fb0870f77877b7c4c7e38a19e0 tests/test_basic.py tests/test_blueprints.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/test_basic.py b/tests/test_basic.py
--- a/tests/test_basic.py
+++ b/tests/test_basic.py
@@ -1631,7 +1631,7 @@ def something_else():
 
 
 def test_inject_blueprint_url_defaults(app):
-    bp = flask.Blueprint("foo.bar.baz", __name__, template_folder="template")
+    bp = flask.Blueprint("foo", __name__, template_folder="template")
 
     @bp.url_defaults
     def bp_defaults(endpoint, values):
@@ -1644,12 +1644,12 @@ def view(page):
     app.register_blueprint(bp)
 
     values = dict()
-    app.inject_url_defaults("foo.bar.baz.view", values)
+    app.inject_url_defaults("foo.view", values)
     expected = dict(page="login")
     assert values == expected
 
     with app.test_request_context("/somepage"):
-        url = flask.url_for("foo.bar.baz.view")
+        url = flask.url_for("foo.view")
     expected = "/login"
     assert url == expected
 
diff --git a/tests/test_blueprints.py b/tests/test_blueprints.py
--- a/tests/test_blueprints.py
+++ b/tests/test_blueprints.py
@@ -1,5 +1,3 @@
-import functools
-
 import pytest
 from jinja2 import TemplateNotFound
 from werkzeug.http import parse_cache_control_header
@@ -253,28 +251,9 @@ def test_templates_list(test_apps):
     assert templates == ["admin/index.html", "frontend/index.html"]
 
 
-def test_dotted_names(app, client):
-    frontend = flask.Blueprint("myapp.frontend", __name__)
-    backend = flask.Blueprint("myapp.backend", __name__)
-
-    @frontend.route("/fe")
-    def frontend_index():
-        return flask.url_for("myapp.backend.backend_index")
-
-    @frontend.route("/fe2")
-    def frontend_page2():
-        return flask.url_for(".frontend_index")
-
-    @backend.route("/be")
-    def backend_index():
-        return flask.url_for("myapp.frontend.frontend_index")
-
-    app.register_blueprint(frontend)
-    app.register_blueprint(backend)
-
-    assert client.get("/fe").data.strip() == b"/be"
-    assert client.get("/fe2").data.strip() == b"/fe"
-    assert client.get("/be").data.strip() == b"/fe"
+def test_dotted_name_not_allowed(app, client):
+    with pytest.raises(ValueError):
+        flask.Blueprint("app.ui", __name__)
 
 
 def test_dotted_names_from_app(app, client):
@@ -343,62 +322,19 @@ def index():
 def test_route_decorator_custom_endpoint_with_dots(app, client):
     bp = flask.Blueprint("bp", __name__)
 
-    @bp.route("/foo")
-    def foo():
-        return flask.request.endpoint
-
-    try:
-
-        @bp.route("/bar", endpoint="bar.bar")
-        def foo_bar():
-            return flask.request.endpoint
-
-    except AssertionError:
-        pass
-    else:
-        raise AssertionError("expected AssertionError not raised")
-
-    try:
-
-        @bp.route("/bar/123", endpoint="bar.123")
-        def foo_bar_foo():
-            return flask.request.endpoint
-
-    except AssertionError:
-        pass
-    else:
-        raise AssertionError("expected AssertionError not raised")
-
-    def foo_foo_foo():
-        pass
-
-    pytest.raises(
-        AssertionError,
-        lambda: bp.add_url_rule("/bar/123", endpoint="bar.123", view_func=foo_foo_foo),
-    )
-
-    pytest.raises(
-        AssertionError, bp.route("/bar/123", endpoint="bar.123"), lambda: None
-    )
-
-    foo_foo_foo.__name__ = "bar.123"
+    with pytest.raises(ValueError):
+        bp.route("/", endpoint="a.b")(lambda: "")
 
-    pytest.raises(
-        AssertionError, lambda: bp.add_url_rule("/bar/123", view_func=foo_foo_foo)
-    )
+    with pytest.raises(ValueError):
+        bp.add_url_rule("/", endpoint="a.b")
 
-    bp.add_url_rule(
-        "/bar/456", endpoint="foofoofoo", view_func=functools.partial(foo_foo_foo)
-    )
+    def view():
+        return ""
 
-    app.register_blueprint(bp, url_prefix="/py")
+    view.__name__ = "a.b"
 
-    assert client.get("/py/foo").data == b"bp.foo"
-    # The rule's didn't actually made it through
-    rv = client.get("/py/bar")
-    assert rv.status_code == 404
-    rv = client.get("/py/bar/123")
-    assert rv.status_code == 404
+    with pytest.raises(ValueError):
+        bp.add_url_rule("/", view_func=view)
 
 
 def test_endpoint_decorator(app, client):

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA tests/test_basic.py tests/test_blueprints.py
: '>>>>> End Test Output'
git checkout d8c37f43724cd9fb0870f77877b7c4c7e38a19e0 tests/test_basic.py tests/test_blueprints.py
