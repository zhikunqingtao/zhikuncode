#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff b8a4da8baa1137f173e7035f104067c7d2ffde22
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -v --no-use-pep517 --no-build-isolation -e .
git checkout b8a4da8baa1137f173e7035f104067c7d2ffde22 sklearn/cluster/tests/test_affinity_propagation.py
git apply -v - <<'EOF_114329324912'
diff --git a/sklearn/cluster/tests/test_affinity_propagation.py b/sklearn/cluster/tests/test_affinity_propagation.py
--- a/sklearn/cluster/tests/test_affinity_propagation.py
+++ b/sklearn/cluster/tests/test_affinity_propagation.py
@@ -152,6 +152,14 @@ def test_affinity_propagation_predict_non_convergence():
     assert_array_equal(np.array([-1, -1, -1]), y)
 
 
+def test_affinity_propagation_non_convergence_regressiontest():
+    X = np.array([[1, 0, 0, 0, 0, 0],
+                  [0, 1, 1, 1, 0, 0],
+                  [0, 0, 1, 0, 0, 1]])
+    af = AffinityPropagation(affinity='euclidean', max_iter=2).fit(X)
+    assert_array_equal(np.array([-1, -1, -1]), af.labels_)
+
+
 def test_equal_similarities_and_preferences():
     # Unequal distances
     X = np.array([[0, 0], [1, 1], [-2, -2]])

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA sklearn/cluster/tests/test_affinity_propagation.py
: '>>>>> End Test Output'
git checkout b8a4da8baa1137f173e7035f104067c7d2ffde22 sklearn/cluster/tests/test_affinity_propagation.py
