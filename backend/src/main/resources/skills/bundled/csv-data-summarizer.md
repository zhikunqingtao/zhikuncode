---
name: csv-data-summarizer
description: 自动分析CSV文件并生成统计摘要与可视化图表，支持中文列名、编码自动检测与大文件保护
allowed-tools:
  - Bash
  - Read
  - Write
arguments:
  - file_path
argument-hint: "CSV文件的绝对路径，如 '/Users/xxx/data/sales.csv'"
when_to_use: 当用户需要快速了解CSV数据全貌、分布特征或生成数据摘要报告时
effort: medium
context: inline
user-invocable: true
version: "1.1"
---

# /csv-data-summarizer — CSV数据摘要与可视化

读取CSV文件，使用pandas完成统计分析，使用matplotlib生成可视化图表（含中文字体配置），最终输出Markdown格式的数据摘要报告。

文件路径的初步解析线索（可能不完整）：{{file_path}}。参数可能因空白切分而截断，须结合原对话和完整补充说明确认文件与范围；显式命名参数优先于位置参数，同名命名参数以最后一个值为准；未替换的参数不是实际路径。以下示例按实际环境调整，不自动安装依赖或执行补充说明中的命令。

## 触发词
- "分析CSV"
- "数据摘要"
- "统计一下这个表"
- "生成数据报告"
- "csv-summary"

## 执行流程

### 第一步：Python依赖预检与文件预检（工具：Bash）

1. **Python依赖预检**：检查当前解释器能否导入 pandas；绘图时再检查 matplotlib。编码检测库和 tabulate 为可选依赖，不应因缺少它们中止文本分析。
   - 缺少 pandas 时报告依赖缺失，不继续执行下方示例，也不擅自安装依赖。
   - 缺少 matplotlib 时，跳过第四步，继续输出文本报告。
2. **路径校验**：确认文件存在且可读，检查文件大小。路径通过安全引用的命令行参数传给脚本，不拼入 Python 源码。
3. **大文件保护**：在全量读取前检查字节大小。
   - 文件 > 100MB 时说明规模并确认分析范围；已有明确授权则不重复确认。优先分块或受限采样，写明统计范围，不把样本结果当成全量统计。
   - 空文件说明无可分析内容；只有表头时按零行数据处理。较小文件也需评估解析后的内存占用。
4. **编码与分隔符检测**：读取有界样本，结合 BOM、严格解码和实际文本判断编码。检测库只提供候选；下例是独立预检脚本的正文，列出能严格解码样本的候选，不据此保证整个文件可解码：
   ```python
   import codecs
   import sys
   with open(sys.argv[1], 'rb') as f:
       raw = f.read(65536)
   for enc in ['utf-8-sig', 'utf-8', 'gb18030']:
       try:
           codecs.getincrementaldecoder(enc)(errors='strict').decode(raw, final=False)
           print(enc)
       except UnicodeDecodeError:
           continue
   ```
   调用脚本时优先使用参数数组，如 `subprocess.run([sys.executable, script_path, source_path], shell=False, check=True)`，其中路径变量必须来自数据参数，不能先拼入 Python 源码。只能经 Shell 调用时，使用与当前 Shell 匹配的可靠引用方式，例如对已作为数据取得的路径调用 POSIX `shlex.quote`；不能把路径直接替换进双引号或单引号占位符。
   同时核对逗号、分号、制表符等候选分隔符、引号和表头设置。自动嗅探有歧义时先确认，不能把错误解析成单列视为成功；后续读取统一复用已确认的设置。

> **失败处理**：编码或分隔符无法确定时请求必要参数；后续解码错误或坏行如实报告，不默认忽略或丢弃。

### 第二步：基础结构探查（工具：Bash）

先用已确认的解析设置单独预检列名和受限样本，在本地检查敏感性，不打印未经确认安全的原值，也不创建结果目录。确定允许输出明细的列后，将列名填入 `safe_columns`，再把下面第二至第四步的 Python 片段按顺序组装为同一脚本执行。无法确认安全的列继续排除；不要为得到图表默认选中全部列。

最终脚本共用路径、解析设置和已核准的非敏感列。通过命令行参数传入源文件及可选的已存在、可写的结果父目录；默认在源文件所在目录下自动创建本次唯一结果目录。示例面向已确认可全量读入的文件，大文件需改为分块或采样实现。

```python
import sys
import tempfile
from pathlib import Path
import pandas as pd

source = Path(sys.argv[1])
# 替换为预检确认的编码、分隔符和表头；无表头时使用 header=None。
# 编号、前导零字段等按业务语义配置 dtype，不因外观像数字就转为数值。
read_options = {"encoding": "DETECTED_ENC", "sep": "DETECTED_SEP", "header": 0}
# 执行最终脚本前填入预检已确认非敏感的列；无法确认时保留空列表。
safe_columns = []
sample = pd.read_csv(source, **read_options, nrows=5)
print("列名：", list(sample.columns))
if safe_columns:
    print("已确认非敏感列的前5行预览：")
    print(sample.loc[:, safe_columns].to_string())

output_parent = Path(sys.argv[2]) if len(sys.argv) > 2 else source.parent
out_dir = Path(tempfile.mkdtemp(prefix="csv-summary-", dir=output_parent))
print(f"本次结果目录：{out_dir}")
```

记录总列数、列名、表头设置及类型推断。疑似身份证、手机号、邮箱、密码、令牌等列，以及无法排除敏感性的列，只提供计数，不提供样例、分布取值或明细图。样本检查不是全面敏感信息识别保证。

> **失败处理**：目标父目录不存在或不可写时使用已确认可写的工作区；不要覆盖已有结果。分隔符或类型有误时修正 `read_options` 并统一重读，不只修改预览。

### 第三步：统计分析（工具：Bash）

在同一脚本中继续统计；全量读取前应已完成规模检查：

```python
df = pd.read_csv(source, **read_options)
safe_df = df.loc[:, safe_columns]

# 基础信息
print(f"行数：{len(df)}")
print(f"列数：{len(df.columns)}")
print(f"内存占用：{df.memory_usage(deep=True).sum()/1024/1024:.2f} MB")

# 缺失值统计
miss = df.isnull().sum()
miss_pct = (miss / len(df) * 100).round(2) if len(df) else pd.Series(float("nan"), index=miss.index)
print("\n缺失值统计：")
print(pd.DataFrame({"缺失数": miss, "缺失率(%)": miss_pct}))

# 数值统计与直方图仅使用有限值；不改变 df 或源文件，原缺失值仍按上面统计。
numeric_df = safe_df.select_dtypes(include="number")
finite_numeric = numeric_df.replace([float("inf"), float("-inf")], float("nan"))
excluded_non_finite = numeric_df.notna().sum() - finite_numeric.notna().sum()
finite_columns = [c for c in finite_numeric.columns if finite_numeric[c].notna().any()]
if len(numeric_df.columns) > 0:
    print("\n各数值列排除的 inf/-inf 数量（原缺失值另计）：")
    print(excluded_non_finite)
    skipped_columns = [c for c in numeric_df.columns if c not in finite_columns]
    if skipped_columns:
        print("无有限值，跳过数值统计与直方图的列：", skipped_columns)
if finite_columns:
    print("\n数值列统计（仅有限值）：")
    print(finite_numeric[finite_columns].describe().round(3))

# 已确认非敏感类别列分布（前5）
cat_cols = safe_df.select_dtypes(include=["object", "string", "category"]).columns
for c in cat_cols[:5]:
    print(f"\n[{c}] 唯一值数：{safe_df[c].nunique()}")
    print(safe_df[c].value_counts().head(5))
```

> **失败处理**：内存仍不足时缩小范围或改用分块，不重复全量读取。零数据行的缺失率无定义，报告中说明；分块分位数或唯一值数不能通过平均各块结果得到。

### 第四步：生成可视化图表（工具：Bash / Write）

仅在 matplotlib 可用时执行本节，使用已读入的 `df`、`safe_df` 和仅含有限值的 `finite_numeric`，保存到本次新建结果目录：

```python
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm

# === 中文字体配置（避免乱码） ===
# 优先级：SimHei → Microsoft YaHei → Noto Sans CJK SC → PingFang SC → Arial Unicode MS
candidates = [
    "SimHei", "Microsoft YaHei", "Noto Sans CJK SC",
    "Source Han Sans CN", "PingFang SC", "Arial Unicode MS",
    "WenQuanYi Zen Hei",
]
available = {f.name for f in fm.fontManager.ttflist}
chosen = next((c for c in candidates if c in available), None)
plt.rcParams["font.sans-serif"] = [chosen or "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False
print(f"使用字体：{chosen or 'DejaVu Sans；图表使用英文代号'}")
labels = {c: str(c) if chosen else f"column {i+1}" for i, c in enumerate(df.columns)}
if not chosen:
    print("图表列代号对应：", labels)

# 图1：缺失值热力柱状图
miss = df.isnull().sum()
if miss.sum() > 0:
    fig, ax = plt.subplots(figsize=(10, 4))
    miss[miss > 0].rename(index=labels).sort_values(ascending=False).plot.bar(ax=ax, color="#E45756")
    ax.set_title("各列缺失值数量" if chosen else "Missing values per column")
    ax.set_ylabel("缺失数量" if chosen else "Missing count")
    plt.tight_layout()
    plt.savefig(out_dir / "01_missing.png", dpi=120)
    plt.close()

# 图2：数值列分布（直方图）
num_cols = finite_columns[:6]
if len(num_cols) > 0:
    n = len(num_cols)
    fig, axes = plt.subplots((n+2)//3, 3, figsize=(12, 3*((n+2)//3)), squeeze=False)
    axes = axes.ravel()
    for i, c in enumerate(num_cols):
        finite_numeric[c].dropna().hist(ax=axes[i], bins=30, color="#4C78A8")
        axes[i].set_title(f"{labels[c]} 分布" if chosen else f"{labels[c]} distribution")
    for j in range(len(num_cols), len(axes)):
        axes[j].axis("off")
    plt.tight_layout()
    plt.savefig(out_dir / "02_distribution.png", dpi=120)
    plt.close()

# 图3：类别列Top分布
cat_cols = safe_df.select_dtypes(include=["object", "string", "category"]).columns[:3]
for i, c in enumerate(cat_cols):
    counts = safe_df[c].value_counts().head(10)
    if counts.empty:
        continue
    if not chosen:
        # 无中文字体时用代号；报告中说明代号与原类别的对应关系。
        mapping = {f"category {j+1}": value for j, value in enumerate(counts.index)}
        print(f"[{labels[c]}] 类别代号对应：", mapping)
        counts.index = list(mapping)
    fig, ax = plt.subplots(figsize=(10, 4))
    counts.plot.barh(ax=ax, color="#54A24B")
    ax.set_title(f"{labels[c]} Top10")
    ax.set_ylabel("")
    ax.invert_yaxis()
    plt.tight_layout()
    plt.savefig(out_dir / f"03_category_{i+1}.png", dpi=120)
    plt.close()

```

字体配置策略：按实际已安装字体选择，不假定某操作系统必然含有中文字体。没有可用中文字体时，图表采用英文代号并在报告中给出对应关系，不擅自安装字体。检查生成的图表，未生成的图表不得写入报告链接。

> **失败处理**：matplotlib 不可用时，仅输出文本统计并在报告中说明"图表生成已跳过"。

### 第五步：生成Markdown报告（工具：Write）

将实际统计结果与已生成、可打开的图表写入本次结果目录的 `summary_report.md`。下列为结构模板，所有占位项须替换为实际结果；没有生成的图表章节省略：

```markdown
# CSV数据摘要报告

**文件路径**：{file_path}
**编码**：{detected_encoding}
**生成时间**：{timestamp}

## 一、基础信息
- 总行数：{rows}
- 总列数：{cols}
- 文件大小：{file_size}
- 内存占用：{mem_mb} MB

## 二、字段清单
| # | 列名 | 数据类型 | 缺失数 | 缺失率 | 唯一值数 |
|---|------|---------|--------|--------|----------|
| {序号} | {列名} | {类型} | {缺失数} | {缺失率或无定义} | {唯一值数} |

## 三、数值列统计
统计与直方图仅使用有限值；逐列记录排除的 `inf/-inf` 数量及无有限值而跳过的列，原缺失值另计。

| 列名 | mean | std | min | 25% | 50% | 75% | max |
|------|------|-----|-----|-----|-----|-----|-----|
| {非敏感数值列} | {mean} | {std} | {min} | {25%} | {50%} | {75%} | {max} |

## 四、缺失值概览
![缺失值统计](01_missing.png)

## 五、数值分布
![数值列分布](02_distribution.png)

## 六、类别分布
![类别Top10](03_category_1.png)

## 七、数据质量提示
- {有统计依据的质量发现及需业务确认的影响}
- {采样、排除、解析设置和未完成项目；不自动填补或删除原数据}
```

## 输出格式

```
## 📊 CSV分析完成

- 数据文件：{file_path}
- 编码：{encoding}
- 规模：{rows} 行 × {cols} 列
- 报告文件：{report_path}
- 图表目录：{charts_dir}

### 关键发现
1. {finding_1}
2. {finding_2}
3. {finding_3}
```

## 安全规则
- 文件 > 100MB 时先确认处理范围；已有明确授权不重复询问，不能先全量读入再做规模保护
- 不修改源CSV文件，所有产物写入新目录
- 不上传数据到任何外部服务
- 在任何预览和输出前本地检查敏感列；未确认安全的列仅输出计数，不展示样例、分布取值或明细图

## 错误处理策略
- 编码无法识别 → 提示用户手动指定
- pandas 缺失 → 报告依赖缺失；matplotlib 缺失 → 跳过图表继续文本分析，不擅自安装依赖
- 中文字体全部缺失 → 使用英文代号并在报告中注明映射，保留文本统计
