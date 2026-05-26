# ZhikunCode (qwen3.7-max)

- Site: https://github.com/zhikunqingtao/zhikuncode
- Report: https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html
- Authors: Qingtao Guo

## Submission Summary

```
Submission summary for 20260525_zhikuncode on SWE-bench lite split
==================================================
Resolved 168 instances (56.0%)
==================================================
```

### Resolved by Repository

| Repository | Resolved / Total | Resolve Rate |
| --- | --- | --- |
| astropy/astropy | 4 / 6 | 66.7% |
| django/django | 82 / 114 | 71.9% |
| matplotlib/matplotlib | 8 / 23 | 34.8% |
| mwaskom/seaborn | 3 / 4 | 75.0% |
| pallets/flask | 0 / 3 | 0.0% |
| psf/requests | 2 / 6 | 33.3% |
| pydata/xarray | 2 / 5 | 40.0% |
| pylint-dev/pylint | 1 / 6 | 16.7% |
| pytest-dev/pytest | 8 / 17 | 47.1% |
| scikit-learn/scikit-learn | 12 / 23 | 52.2% |
| sphinx-doc/sphinx | 4 / 16 | 25.0% |
| sympy/sympy | 42 / 77 | 54.5% |
| **Total** | **168 / 300** | **56.0%** |

### Aggregate Counts

- total_instances: 300
- submitted_instances: 300
- completed_instances: 276
- resolved_instances: 168
- unresolved_instances: 108
- empty_patch_instances: 16
- error_instances: 8

### Resolved Instances by Repository

**astropy/astropy (4)**
- astropy__astropy-12907, astropy__astropy-14182, astropy__astropy-14995, astropy__astropy-6938

**django/django (82)**
- django__django-10914, django__django-10924, django__django-11001, django__django-11039, django__django-11049, django__django-11099, django__django-11133, django__django-11179, django__django-11422, django__django-11583, django__django-11620, django__django-11742, django__django-11815, django__django-11848, django__django-11910, django__django-11964, django__django-11999, django__django-12113, django__django-12125, django__django-12184, django__django-12284, django__django-12286, django__django-12453, django__django-12470, django__django-12497, django__django-12700, django__django-12708, django__django-12856, django__django-12908, django__django-12915, django__django-12983, django__django-13028, django__django-13033, django__django-13158, django__django-13220, django__django-13230, django__django-13315, django__django-13401, django__django-13447, django__django-13590, django__django-13658, django__django-13710, django__django-13757, django__django-13768, django__django-13925, django__django-13933, django__django-13964, django__django-14016, django__django-14017, django__django-14238, django__django-14382, django__django-14411, django__django-14580, django__django-14608, django__django-14672, django__django-14752, django__django-14787, django__django-14855, django__django-14915, django__django-14999, django__django-15061, django__django-15213, django__django-15347, django__django-15400, django__django-15498, django__django-15790, django__django-15814, django__django-15851, django__django-15902, django__django-16041, django__django-16046, django__django-16139, django__django-16229, django__django-16255, django__django-16379, django__django-16408, django__django-16527, django__django-16595, django__django-16816, django__django-16873, django__django-17051, django__django-17087

**matplotlib/matplotlib (8)**
- matplotlib__matplotlib-23299, matplotlib__matplotlib-23476, matplotlib__matplotlib-23562, matplotlib__matplotlib-23913, matplotlib__matplotlib-23964, matplotlib__matplotlib-25311, matplotlib__matplotlib-25332, matplotlib__matplotlib-25442

**mwaskom/seaborn (3)**
- mwaskom__seaborn-2848, mwaskom__seaborn-3010, mwaskom__seaborn-3190

**psf/requests (2)**
- psf__requests-1963, psf__requests-3362

**pydata/xarray (2)**
- pydata__xarray-4094, pydata__xarray-5131

**pylint-dev/pylint (1)**
- pylint-dev__pylint-5859

**pytest-dev/pytest (8)**
- pytest-dev__pytest-11143, pytest-dev__pytest-5227, pytest-dev__pytest-5495, pytest-dev__pytest-6116, pytest-dev__pytest-7168, pytest-dev__pytest-7373, pytest-dev__pytest-7432, pytest-dev__pytest-9359

**scikit-learn/scikit-learn (12)**
- scikit-learn__scikit-learn-10297, scikit-learn__scikit-learn-11281, scikit-learn__scikit-learn-13142, scikit-learn__scikit-learn-13439, scikit-learn__scikit-learn-13497, scikit-learn__scikit-learn-13584, scikit-learn__scikit-learn-13779, scikit-learn__scikit-learn-14092, scikit-learn__scikit-learn-14894, scikit-learn__scikit-learn-14983, scikit-learn__scikit-learn-15512, scikit-learn__scikit-learn-15535

**sphinx-doc/sphinx (4)**
- sphinx-doc__sphinx-10325, sphinx-doc__sphinx-11445, sphinx-doc__sphinx-8627, sphinx-doc__sphinx-8713

**sympy/sympy (42)**
- sympy__sympy-11897, sympy__sympy-12236, sympy__sympy-12419, sympy__sympy-12481, sympy__sympy-13471, sympy__sympy-13480, sympy__sympy-13647, sympy__sympy-13971, sympy__sympy-14024, sympy__sympy-14396, sympy__sympy-14774, sympy__sympy-14817, sympy__sympy-15011, sympy__sympy-15345, sympy__sympy-15346, sympy__sympy-15609, sympy__sympy-15678, sympy__sympy-16792, sympy__sympy-17022, sympy__sympy-17139, sympy__sympy-17655, sympy__sympy-18057, sympy__sympy-18087, sympy__sympy-18189, sympy__sympy-18532, sympy__sympy-18621, sympy__sympy-18698, sympy__sympy-19007, sympy__sympy-20154, sympy__sympy-20212, sympy__sympy-20442, sympy__sympy-21055, sympy__sympy-21379, sympy__sympy-21614, sympy__sympy-21627, sympy__sympy-21847, sympy__sympy-22714, sympy__sympy-23117, sympy__sympy-23262, sympy__sympy-24066, sympy__sympy-24152, sympy__sympy-24213

## Checklist

- [x] Is a pass@1 submission (does not attempt the same task instance more than once)
- [x] Does not use SWE-bench test knowledge (`PASS_TO_PASS`, `FAIL_TO_PASS`)
- [x] Does not use the `hints` field in SWE-bench
- [x] Does not have web-browsing OR has taken steps to prevent lookup of SWE-bench solutions via web-browsing

## System Description

ZhikunCode is an AI coding agent built on **Qwen 3.7 Max** (Alibaba Cloud DashScope) that solves SWE-bench Lite issues through multi-turn agentic interaction. The agent runs in **multiphase mode** with up to **60 turns per session** and a **1200-second per-turn timeout**, iteratively reading the repository, locating the defect, editing the affected files, and validating the result inside an isolated sandbox.

The agent operates on a closed action set of file-editing and shell-execution tools — **Read, Edit, Write, Bash, Grep, Glob** — wrapped by a deterministic Agent Loop with prompt isolation, BashTool error recovery, and per-turn context-budget enforcement. No browser is used, and the SWE-bench oracle fields (`PASS_TO_PASS`, `FAIL_TO_PASS`, `hints_text`) are never read; the agent only sees the issue text and the repository checked out at the base commit. All results are **Pass@1** with a single attempt per instance and no inference-time ensembling.
