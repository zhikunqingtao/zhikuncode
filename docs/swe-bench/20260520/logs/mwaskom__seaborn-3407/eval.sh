#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 515286e02be3e4c0ff2ef4addb34a53c4a676ee4
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .[dev]
git checkout 515286e02be3e4c0ff2ef4addb34a53c4a676ee4 tests/test_axisgrid.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/test_axisgrid.py b/tests/test_axisgrid.py
--- a/tests/test_axisgrid.py
+++ b/tests/test_axisgrid.py
@@ -1422,6 +1422,13 @@ def test_pairplot_markers(self):
         with pytest.warns(UserWarning):
             g = ag.pairplot(self.df, hue="a", vars=vars, markers=markers[:-2])
 
+    def test_pairplot_column_multiindex(self):
+
+        cols = pd.MultiIndex.from_arrays([["x", "y"], [1, 2]])
+        df = self.df[["x", "y"]].set_axis(cols, axis=1)
+        g = ag.pairplot(df)
+        assert g.diag_vars == list(cols)
+
     def test_corner_despine(self):
 
         g = ag.PairGrid(self.df, corner=True, despine=False)

EOF_114329324912
: '>>>>> Start Test Output'
pytest --no-header -rA tests/test_axisgrid.py
: '>>>>> End Test Output'
git checkout 515286e02be3e4c0ff2ef4addb34a53c4a676ee4 tests/test_axisgrid.py
