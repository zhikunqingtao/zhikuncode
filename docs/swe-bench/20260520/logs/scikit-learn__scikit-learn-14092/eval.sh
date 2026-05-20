#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff df7dd8391148a873d157328a4f0328528a0c4ed9
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -v --no-use-pep517 --no-build-isolation -e .
git checkout df7dd8391148a873d157328a4f0328528a0c4ed9 sklearn/neighbors/tests/test_nca.py
git apply -v - <<'EOF_114329324912'
diff --git a/sklearn/neighbors/tests/test_nca.py b/sklearn/neighbors/tests/test_nca.py
--- a/sklearn/neighbors/tests/test_nca.py
+++ b/sklearn/neighbors/tests/test_nca.py
@@ -129,7 +129,7 @@ def test_params_validation():
     # TypeError
     assert_raises(TypeError, NCA(max_iter='21').fit, X, y)
     assert_raises(TypeError, NCA(verbose='true').fit, X, y)
-    assert_raises(TypeError, NCA(tol=1).fit, X, y)
+    assert_raises(TypeError, NCA(tol='1').fit, X, y)
     assert_raises(TypeError, NCA(n_components='invalid').fit, X, y)
     assert_raises(TypeError, NCA(warm_start=1).fit, X, y)
 
@@ -518,3 +518,17 @@ def test_convergence_warning():
     assert_warns_message(ConvergenceWarning,
                          '[{}] NCA did not converge'.format(cls_name),
                          nca.fit, iris_data, iris_target)
+
+
+@pytest.mark.parametrize('param, value', [('n_components', np.int32(3)),
+                                          ('max_iter', np.int32(100)),
+                                          ('tol', np.float32(0.0001))])
+def test_parameters_valid_types(param, value):
+    # check that no error is raised when parameters have numpy integer or
+    # floating types.
+    nca = NeighborhoodComponentsAnalysis(**{param: value})
+
+    X = iris_data
+    y = iris_target
+
+    nca.fit(X, y)

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA sklearn/neighbors/tests/test_nca.py
: '>>>>> End Test Output'
git checkout df7dd8391148a873d157328a4f0328528a0c4ed9 sklearn/neighbors/tests/test_nca.py
