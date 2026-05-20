#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 3b5abf76597ce6aff76192869f92647c1b5259e7
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -v --no-use-pep517 --no-build-isolation -e .
git checkout 3b5abf76597ce6aff76192869f92647c1b5259e7 sklearn/utils/tests/test_validation.py
git apply -v - <<'EOF_114329324912'
diff --git a/sklearn/utils/tests/test_validation.py b/sklearn/utils/tests/test_validation.py
--- a/sklearn/utils/tests/test_validation.py
+++ b/sklearn/utils/tests/test_validation.py
@@ -7,6 +7,7 @@
 from itertools import product
 
 import pytest
+from pytest import importorskip
 import numpy as np
 import scipy.sparse as sp
 from scipy import __version__ as scipy_version
@@ -713,6 +714,38 @@ def test_suppress_validation():
     assert_raises(ValueError, assert_all_finite, X)
 
 
+def test_check_dataframe_warns_on_dtype():
+    # Check that warn_on_dtype also works for DataFrames.
+    # https://github.com/scikit-learn/scikit-learn/issues/10948
+    pd = importorskip("pandas")
+
+    df = pd.DataFrame([[1, 2, 3], [4, 5, 6]], dtype=object)
+    assert_warns_message(DataConversionWarning,
+                         "Data with input dtype object were all converted to "
+                         "float64.",
+                         check_array, df, dtype=np.float64, warn_on_dtype=True)
+    assert_warns(DataConversionWarning, check_array, df,
+                 dtype='numeric', warn_on_dtype=True)
+    assert_no_warnings(check_array, df, dtype='object', warn_on_dtype=True)
+
+    # Also check that it raises a warning for mixed dtypes in a DataFrame.
+    df_mixed = pd.DataFrame([['1', 2, 3], ['4', 5, 6]])
+    assert_warns(DataConversionWarning, check_array, df_mixed,
+                 dtype=np.float64, warn_on_dtype=True)
+    assert_warns(DataConversionWarning, check_array, df_mixed,
+                 dtype='numeric', warn_on_dtype=True)
+    assert_warns(DataConversionWarning, check_array, df_mixed,
+                 dtype=object, warn_on_dtype=True)
+
+    # Even with numerical dtypes, a conversion can be made because dtypes are
+    # uniformized throughout the array.
+    df_mixed_numeric = pd.DataFrame([[1., 2, 3], [4., 5, 6]])
+    assert_warns(DataConversionWarning, check_array, df_mixed_numeric,
+                 dtype='numeric', warn_on_dtype=True)
+    assert_no_warnings(check_array, df_mixed_numeric.astype(int),
+                       dtype='numeric', warn_on_dtype=True)
+
+
 class DummyMemory(object):
     def cache(self, func):
         return func

EOF_114329324912
: '>>>>> Start Test Output'
pytest -rA sklearn/utils/tests/test_validation.py
: '>>>>> End Test Output'
git checkout 3b5abf76597ce6aff76192869f92647c1b5259e7 sklearn/utils/tests/test_validation.py
