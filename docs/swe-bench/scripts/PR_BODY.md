## ZhikunCode - SWE-bench Lite Results

**Resolve Rate: 139/300 = 46.3%**

### System Description
ZhikunCode is an open-source multi-agent AI coding system built on Qwen 3.6 Max Preview (262K context). It employs a four-phase approach (ANALYZE → LOCATE → FIX → VERIFY) with five-layer context compression and dual-layer self-correction.

### Key Features
- Pass@1 single-attempt inference (no retry/filtering)
- Closed tool set: Read, Edit, Write, Bash, Grep, Glob
- No test leakage, no oracle hints, no web browsing
- Open-source: https://github.com/zhikunqingtao/zhikuncode

### Compliance Checklist
- [x] Pass@1 evaluation (single attempt per instance)
- [x] No test content in prompts or generation
- [x] No oracle hints or ground truth usage
- [x] No web browsing during inference

### Files
- `all_preds.jsonl`: 300 predictions
- `metadata.yaml`: Model metadata
- `README.md`: Detailed results and methodology
- `logs/`: Per-instance evaluation logs (251 report.json)
- `trajs/`: Per-instance inference trajectories (300 files)

### Tech Report
https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html
