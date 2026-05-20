# ZhikunCode (qwen3.6-max-preview)

- Site: https://github.com/zhikunqingtao/zhikuncode
- Report: https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html
- Authors: Qingtao Guo

## Submission Summary

```
Submission summary for 20260520_zhikuncode on SWE-bench lite split
==================================================
Resolved 139 instances (46.3%)
==================================================
```

### Resolved by Repository

| Repository | Resolved / Total | Resolve Rate |
| --- | --- | --- |
| astropy/astropy | 2 / 6 | 33.3% |
| django/django | 69 / 114 | 60.5% |
| matplotlib/matplotlib | 4 / 23 | 17.4% |
| mwaskom/seaborn | 3 / 4 | 75.0% |
| pallets/flask | 1 / 3 | 33.3% |
| psf/requests | 3 / 6 | 50.0% |
| pydata/xarray | 2 / 5 | 40.0% |
| pylint-dev/pylint | 1 / 6 | 16.7% |
| pytest-dev/pytest | 7 / 17 | 41.2% |
| scikit-learn/scikit-learn | 6 / 23 | 26.1% |
| sphinx-doc/sphinx | 3 / 16 | 18.8% |
| sympy/sympy | 38 / 77 | 49.4% |
| **Total** | **139 / 300** | **46.3%** |

### Aggregate Counts

- total_instances: 300
- submitted_instances: 300
- completed_instances: 251
- resolved_instances: 139
- unresolved_instances: 112
- empty_patch_instances: 20
- error_instances: 29

### Resolved Instances by Repository

**astropy/astropy (2)**
- astropy__astropy-12907
- astropy__astropy-14995

**django/django (69)**
- django__django-10924, django__django-11001, django__django-11039, django__django-11049, django__django-11133, django__django-11179, django__django-11422, django__django-11583, django__django-11797, django__django-11815, django__django-11848, django__django-11910, django__django-11999, django__django-12125, django__django-12184, django__django-12284, django__django-12286, django__django-12453, django__django-12497, django__django-12589, django__django-12700, django__django-12708, django__django-12747, django__django-12856, django__django-12908, django__django-12983, django__django-13028, django__django-13033, django__django-13158, django__django-13230, django__django-13315, django__django-13401, django__django-13658, django__django-13710, django__django-13757, django__django-13933, django__django-13964, django__django-14016, django__django-14238, django__django-14382, django__django-14411, django__django-14580, django__django-14608, django__django-14672, django__django-14752, django__django-14787, django__django-14855, django__django-14915, django__django-14999, django__django-15213, django__django-15347, django__django-15400, django__django-15498, django__django-15695, django__django-15738, django__django-15790, django__django-15814, django__django-15851, django__django-16046, django__django-16139, django__django-16229, django__django-16255, django__django-16379, django__django-16527, django__django-16595, django__django-16873, django__django-16910, django__django-17051, django__django-17087

**matplotlib/matplotlib (4)**
- matplotlib__matplotlib-23562, matplotlib__matplotlib-23913, matplotlib__matplotlib-25442, matplotlib__matplotlib-26020

**mwaskom/seaborn (3)**
- mwaskom__seaborn-2848, mwaskom__seaborn-3010, mwaskom__seaborn-3190

**pallets/flask (1)**
- pallets__flask-4992

**psf/requests (3)**
- psf__requests-1963, psf__requests-3362, psf__requests-863

**pydata/xarray (2)**
- pydata__xarray-4094, pydata__xarray-5131

**pylint-dev/pylint (1)**
- pylint-dev__pylint-5859

**pytest-dev/pytest (7)**
- pytest-dev__pytest-11143, pytest-dev__pytest-5103, pytest-dev__pytest-5227, pytest-dev__pytest-5495, pytest-dev__pytest-7168, pytest-dev__pytest-7373, pytest-dev__pytest-7432

**scikit-learn/scikit-learn (6)**
- scikit-learn__scikit-learn-10297, scikit-learn__scikit-learn-14092, scikit-learn__scikit-learn-14894, scikit-learn__scikit-learn-14983, scikit-learn__scikit-learn-15512, scikit-learn__scikit-learn-15535

**sphinx-doc/sphinx (3)**
- sphinx-doc__sphinx-10325, sphinx-doc__sphinx-8627, sphinx-doc__sphinx-8713

**sympy/sympy (38)**
- sympy__sympy-12236, sympy__sympy-12419, sympy__sympy-12481, sympy__sympy-13471, sympy__sympy-13480, sympy__sympy-13647, sympy__sympy-13971, sympy__sympy-14396, sympy__sympy-14774, sympy__sympy-14817, sympy__sympy-15345, sympy__sympy-15346, sympy__sympy-15609, sympy__sympy-15678, sympy__sympy-16792, sympy__sympy-17022, sympy__sympy-17139, sympy__sympy-17655, sympy__sympy-18057, sympy__sympy-18087, sympy__sympy-18189, sympy__sympy-18532, sympy__sympy-18621, sympy__sympy-18698, sympy__sympy-20154, sympy__sympy-20212, sympy__sympy-20442, sympy__sympy-21055, sympy__sympy-21379, sympy__sympy-21612, sympy__sympy-21614, sympy__sympy-21847, sympy__sympy-22005, sympy__sympy-23117, sympy__sympy-23262, sympy__sympy-24066, sympy__sympy-24152, sympy__sympy-24213

## Checklist

- [x] Is a pass@1 submission (does not attempt the same task instance more than once)
- [x] Does not use SWE-bench test knowledge (`PASS_TO_PASS`, `FAIL_TO_PASS`)
- [x] Does not use the `hints` field in SWE-bench
- [x] Does not have web-browsing OR has taken steps to prevent lookup of SWE-bench solutions via web-browsing

## System Description

ZhikunCode is a multi-stage agent system built on **Qwen 3.6 Max Preview** (262K context window) that solves SWE-bench Lite issues through a four-phase loop: **ANALYZE → LOCATE → FIX → VERIFY**. Each phase has an isolated prompt and dedicated context budget, with a five-layer context-compression pipeline (raw repo trim → semantic chunk pruning → diff-aware retention → cross-phase summarization → token-budget enforcement) and a dual-loop self-correction mechanism (intra-phase patch retry + cross-phase verification rollback) that stabilizes long-horizon edits.

The agent operates on a closed, six-tool action set: **Read, Edit, Write, Bash, Grep, Glob**. No browser, no SWE-bench oracle fields (`PASS_TO_PASS`, `FAIL_TO_PASS`, `hints_text`), and no test-suite knowledge are used; the agent only sees the issue text and the repository at the base commit. All results are **Pass@1** with a single attempt per instance and no inference-time ensembling.
