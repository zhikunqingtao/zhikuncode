#!/bin/bash
set -uxo pipefail
source /opt/miniconda3/bin/activate
conda activate testbed
cd /testbed
git config --global --add safe.directory /testbed
cd /testbed
git status
git show
git -c core.fileMode=false diff 332d80ba8433aea41c3709fa52737ede4405072b
source /opt/miniconda3/bin/activate
conda activate testbed
python -m pip install -e .[test]
git checkout 332d80ba8433aea41c3709fa52737ede4405072b tests/test_util_typing.py
git apply -v - <<'EOF_114329324912'
diff --git a/tests/test_util_typing.py b/tests/test_util_typing.py
--- a/tests/test_util_typing.py
+++ b/tests/test_util_typing.py
@@ -10,6 +10,7 @@
 
 import sys
 from numbers import Integral
+from struct import Struct
 from typing import (Any, Callable, Dict, Generator, List, NewType, Optional, Tuple, TypeVar,
                     Union)
 
@@ -43,6 +44,7 @@ def test_restify():
     assert restify(str) == ":class:`str`"
     assert restify(None) == ":obj:`None`"
     assert restify(Integral) == ":class:`numbers.Integral`"
+    assert restify(Struct) == ":class:`struct.Struct`"
     assert restify(Any) == ":obj:`Any`"
 
 
@@ -124,6 +126,7 @@ def test_stringify():
     assert stringify(str) == "str"
     assert stringify(None) == "None"
     assert stringify(Integral) == "numbers.Integral"
+    assert restify(Struct) == ":class:`struct.Struct`"
     assert stringify(Any) == "Any"
 
 

EOF_114329324912
: '>>>>> Start Test Output'
tox --current-env -epy39 -v -- tests/test_util_typing.py
: '>>>>> End Test Output'
git checkout 332d80ba8433aea41c3709fa52737ede4405072b tests/test_util_typing.py
