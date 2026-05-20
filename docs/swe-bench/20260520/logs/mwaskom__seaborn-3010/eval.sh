#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 0f5a013e2cf43562deec3b879458e59a73853813
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .[dev]
git checkout 0f5a013e2cf43562deec3b879458e59a73853813 tests/_stats/test_regression.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/_stats/test_regression.py b/tests/_stats/test_regression.py
--- a/tests/_stats/test_regression.py
+++ b/tests/_stats/test_regression.py
@@ -4,6 +4,7 @@
 
 import pytest
 from numpy.testing import assert_array_equal, assert_array_almost_equal
+from pandas.testing import assert_frame_equal
 
 from seaborn._core.groupby import GroupBy
 from seaborn._stats.regression import PolyFit
@@ -50,3 +51,11 @@ def test_one_grouper(self, df):
             grid = np.linspace(part["x"].min(), part["x"].max(), gridsize)
             assert_array_equal(part["x"], grid)
             assert part["y"].diff().diff().dropna().abs().gt(0).all()
+
+    def test_missing_data(self, df):
+
+        groupby = GroupBy(["group"])
+        df.iloc[5:10] = np.nan
+        res1 = PolyFit()(df[["x", "y"]], groupby, "x", {})
+        res2 = PolyFit()(df[["x", "y"]].dropna(), groupby, "x", {})
+        assert_frame_equal(res1, res2)
\ No newline at end of file

EOF_114329324912
: '>>>>> Start Test Output'
pytest --no-header -rA tests/_stats/test_regression.py
: '>>>>> End Test Output'
git checkout 0f5a013e2cf43562deec3b879458e59a73853813 tests/_stats/test_regression.py
