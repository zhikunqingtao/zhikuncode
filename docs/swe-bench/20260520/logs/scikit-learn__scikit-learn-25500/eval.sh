#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 4db04923a754b6a2defa1b172f55d492b85d165e
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -v --no-use-pep517 --no-build-isolation -e .
git checkout 4db04923a754b6a2defa1b172f55d492b85d165e sklearn/tests/test_isotonic.py
git apply -v - <<'EOF_114329324912'
diff --git a/sklearn/tests/test_isotonic.py b/sklearn/tests/test_isotonic.py
--- a/sklearn/tests/test_isotonic.py
+++ b/sklearn/tests/test_isotonic.py
@@ -5,6 +5,7 @@
 
 import pytest
 
+import sklearn
 from sklearn.datasets import make_regression
 from sklearn.isotonic import (
     check_increasing,
@@ -680,3 +681,24 @@ def test_get_feature_names_out(shape):
     assert isinstance(names, np.ndarray)
     assert names.dtype == object
     assert_array_equal(["isotonicregression0"], names)
+
+
+def test_isotonic_regression_output_predict():
+    """Check that `predict` does return the expected output type.
+
+    We need to check that `transform` will output a DataFrame and a NumPy array
+    when we set `transform_output` to `pandas`.
+
+    Non-regression test for:
+    https://github.com/scikit-learn/scikit-learn/issues/25499
+    """
+    pd = pytest.importorskip("pandas")
+    X, y = make_regression(n_samples=10, n_features=1, random_state=42)
+    regressor = IsotonicRegression()
+    with sklearn.config_context(transform_output="pandas"):
+        regressor.fit(X, y)
+        X_trans = regressor.transform(X)
+        y_pred = regressor.predict(X)
+
+    assert isinstance(X_trans, pd.DataFrame)
+    assert isinstance(y_pred, np.ndarray)

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA sklearn/tests/test_isotonic.py
: '>>>>> End Test Output'
git checkout 4db04923a754b6a2defa1b172f55d492b85d165e sklearn/tests/test_isotonic.py
