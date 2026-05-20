#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 96a02f3934952d486589dddd3f00b40d5a5ab5f2
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -v --no-use-pep517 --no-build-isolation -e .
git checkout 96a02f3934952d486589dddd3f00b40d5a5ab5f2 sklearn/neighbors/tests/test_neighbors.py
git apply -v - <<'EOF_114329324912'
diff --git a/sklearn/neighbors/tests/test_neighbors.py b/sklearn/neighbors/tests/test_neighbors.py
--- a/sklearn/neighbors/tests/test_neighbors.py
+++ b/sklearn/neighbors/tests/test_neighbors.py
@@ -18,6 +18,7 @@
 from sklearn.utils.testing import assert_greater
 from sklearn.utils.testing import assert_in
 from sklearn.utils.testing import assert_raises
+from sklearn.utils.testing import assert_raises_regex
 from sklearn.utils.testing import assert_true
 from sklearn.utils.testing import assert_warns
 from sklearn.utils.testing import assert_warns_message
@@ -108,6 +109,21 @@ def test_unsupervised_inputs():
         assert_array_almost_equal(ind1, ind2)
 
 
+def test_n_neighbors_datatype():
+    # Test to check whether n_neighbors is integer
+    X = [[1, 1], [1, 1], [1, 1]]
+    expected_msg = "n_neighbors does not take .*float.* " \
+                   "value, enter integer value"
+    msg = "Expected n_neighbors > 0. Got -3"
+
+    neighbors_ = neighbors.NearestNeighbors(n_neighbors=3.)
+    assert_raises_regex(TypeError, expected_msg, neighbors_.fit, X)
+    assert_raises_regex(ValueError, msg,
+                        neighbors_.kneighbors, X=X, n_neighbors=-3)
+    assert_raises_regex(TypeError, expected_msg,
+                        neighbors_.kneighbors, X=X, n_neighbors=3.)
+
+
 def test_precomputed(random_state=42):
     """Tests unsupervised NearestNeighbors with a distance matrix."""
     # Note: smaller samples may result in spurious test success

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA sklearn/neighbors/tests/test_neighbors.py
: '>>>>> End Test Output'
git checkout 96a02f3934952d486589dddd3f00b40d5a5ab5f2 sklearn/neighbors/tests/test_neighbors.py
