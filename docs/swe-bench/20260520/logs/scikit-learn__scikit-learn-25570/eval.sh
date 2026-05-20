#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff cd25abee0ad0ac95225d4a9be8948eff69f49690
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -v --no-use-pep517 --no-build-isolation -e .
git checkout cd25abee0ad0ac95225d4a9be8948eff69f49690 sklearn/compose/tests/test_column_transformer.py
git apply -v - <<'EOF_114329324912'
diff --git a/sklearn/compose/tests/test_column_transformer.py b/sklearn/compose/tests/test_column_transformer.py
--- a/sklearn/compose/tests/test_column_transformer.py
+++ b/sklearn/compose/tests/test_column_transformer.py
@@ -2129,3 +2129,32 @@ def test_transformers_with_pandas_out_but_not_feature_names_out(
     ct.set_params(verbose_feature_names_out=False)
     X_trans_df1 = ct.fit_transform(X_df)
     assert_array_equal(X_trans_df1.columns, expected_non_verbose_names)
+
+
+@pytest.mark.parametrize(
+    "empty_selection",
+    [[], np.array([False, False]), [False, False]],
+    ids=["list", "bool", "bool_int"],
+)
+def test_empty_selection_pandas_output(empty_selection):
+    """Check that pandas output works when there is an empty selection.
+
+    Non-regression test for gh-25487
+    """
+    pd = pytest.importorskip("pandas")
+
+    X = pd.DataFrame([[1.0, 2.2], [3.0, 1.0]], columns=["a", "b"])
+    ct = ColumnTransformer(
+        [
+            ("categorical", "passthrough", empty_selection),
+            ("numerical", StandardScaler(), ["a", "b"]),
+        ],
+        verbose_feature_names_out=True,
+    )
+    ct.set_output(transform="pandas")
+    X_out = ct.fit_transform(X)
+    assert_array_equal(X_out.columns, ["numerical__a", "numerical__b"])
+
+    ct.set_params(verbose_feature_names_out=False)
+    X_out = ct.fit_transform(X)
+    assert_array_equal(X_out.columns, ["a", "b"])

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA sklearn/compose/tests/test_column_transformer.py
: '>>>>> End Test Output'
git checkout cd25abee0ad0ac95225d4a9be8948eff69f49690 sklearn/compose/tests/test_column_transformer.py
