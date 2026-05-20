#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 6adb209acd63825affc884abcd85381f148fb1b0
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -v --no-use-pep517 --no-build-isolation -e .
git checkout 6adb209acd63825affc884abcd85381f148fb1b0 sklearn/metrics/tests/test_classification.py sklearn/preprocessing/tests/test_label.py sklearn/utils/tests/test_multiclass.py
git apply -v - <<'EOF_114329324912'
diff --git a/sklearn/metrics/tests/test_classification.py b/sklearn/metrics/tests/test_classification.py
--- a/sklearn/metrics/tests/test_classification.py
+++ b/sklearn/metrics/tests/test_classification.py
@@ -1079,6 +1079,24 @@ def test_confusion_matrix_dtype():
     assert cm[1, 1] == -2
 
 
+@pytest.mark.parametrize("dtype", ["Int64", "Float64", "boolean"])
+def test_confusion_matrix_pandas_nullable(dtype):
+    """Checks that confusion_matrix works with pandas nullable dtypes.
+
+    Non-regression test for gh-25635.
+    """
+    pd = pytest.importorskip("pandas")
+
+    y_ndarray = np.array([1, 0, 0, 1, 0, 1, 1, 0, 1])
+    y_true = pd.Series(y_ndarray, dtype=dtype)
+    y_predicted = pd.Series([0, 0, 1, 1, 0, 1, 1, 1, 1], dtype="int64")
+
+    output = confusion_matrix(y_true, y_predicted)
+    expected_output = confusion_matrix(y_ndarray, y_predicted)
+
+    assert_array_equal(output, expected_output)
+
+
 def test_classification_report_multiclass():
     # Test performance report
     iris = datasets.load_iris()
diff --git a/sklearn/preprocessing/tests/test_label.py b/sklearn/preprocessing/tests/test_label.py
--- a/sklearn/preprocessing/tests/test_label.py
+++ b/sklearn/preprocessing/tests/test_label.py
@@ -117,6 +117,22 @@ def test_label_binarizer_set_label_encoding():
     assert_array_equal(lb.inverse_transform(got), inp)
 
 
+@pytest.mark.parametrize("dtype", ["Int64", "Float64", "boolean"])
+def test_label_binarizer_pandas_nullable(dtype):
+    """Checks that LabelBinarizer works with pandas nullable dtypes.
+
+    Non-regression test for gh-25637.
+    """
+    pd = pytest.importorskip("pandas")
+    from sklearn.preprocessing import LabelBinarizer
+
+    y_true = pd.Series([1, 0, 0, 1, 0, 1, 1, 0, 1], dtype=dtype)
+    lb = LabelBinarizer().fit(y_true)
+    y_out = lb.transform([1, 0])
+
+    assert_array_equal(y_out, [[1], [0]])
+
+
 @ignore_warnings
 def test_label_binarizer_errors():
     # Check that invalid arguments yield ValueError
diff --git a/sklearn/utils/tests/test_multiclass.py b/sklearn/utils/tests/test_multiclass.py
--- a/sklearn/utils/tests/test_multiclass.py
+++ b/sklearn/utils/tests/test_multiclass.py
@@ -346,6 +346,42 @@ def test_type_of_target_pandas_sparse():
         type_of_target(y)
 
 
+def test_type_of_target_pandas_nullable():
+    """Check that type_of_target works with pandas nullable dtypes."""
+    pd = pytest.importorskip("pandas")
+
+    for dtype in ["Int32", "Float32"]:
+        y_true = pd.Series([1, 0, 2, 3, 4], dtype=dtype)
+        assert type_of_target(y_true) == "multiclass"
+
+        y_true = pd.Series([1, 0, 1, 0], dtype=dtype)
+        assert type_of_target(y_true) == "binary"
+
+    y_true = pd.DataFrame([[1.4, 3.1], [3.1, 1.4]], dtype="Float32")
+    assert type_of_target(y_true) == "continuous-multioutput"
+
+    y_true = pd.DataFrame([[0, 1], [1, 1]], dtype="Int32")
+    assert type_of_target(y_true) == "multilabel-indicator"
+
+    y_true = pd.DataFrame([[1, 2], [3, 1]], dtype="Int32")
+    assert type_of_target(y_true) == "multiclass-multioutput"
+
+
+@pytest.mark.parametrize("dtype", ["Int64", "Float64", "boolean"])
+def test_unique_labels_pandas_nullable(dtype):
+    """Checks that unique_labels work with pandas nullable dtypes.
+
+    Non-regression test for gh-25634.
+    """
+    pd = pytest.importorskip("pandas")
+
+    y_true = pd.Series([1, 0, 0, 1, 0, 1, 1, 0, 1], dtype=dtype)
+    y_predicted = pd.Series([0, 0, 1, 1, 0, 1, 1, 1, 1], dtype="int64")
+
+    labels = unique_labels(y_true, y_predicted)
+    assert_array_equal(labels, [0, 1])
+
+
 def test_class_distribution():
     y = np.array(
         [

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA sklearn/metrics/tests/test_classification.py sklearn/preprocessing/tests/test_label.py sklearn/utils/tests/test_multiclass.py
: '>>>>> End Test Output'
git checkout 6adb209acd63825affc884abcd85381f148fb1b0 sklearn/metrics/tests/test_classification.py sklearn/preprocessing/tests/test_label.py sklearn/utils/tests/test_multiclass.py
