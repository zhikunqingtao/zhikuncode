---
name: csv-data-summarizer
description: 自动分析CSV文件并生成统计摘要与可视化图表，支持中文列名、编码自动检测与大文件保护
allowed-tools: [Bash, Read, Write]
arguments: [file_path]
argument-hint: "CSV文件的绝对路径，如 '/Users/xxx/data/sales.csv'"
when_to_use: 当用户需要快速了解CSV数据全貌、分布特征或生成数据摘要报告时
effort: medium
context: inline
user-invocable: true
version: "1.0"
---

# /csv-data-summarizer — CSV数据摘要与可视化

读取CSV文件，使用pandas完成统计分析，使用matplotlib生成可视化图表（含中文字体配置），最终输出Markdown格式的数据摘要报告。

## 触发词
- "分析CSV"
- "数据摘要"
- "统计一下这个表"
- "生成数据报告"
- "csv-summary"

## 执行流程

### 第一步：Python依赖预检与文件预检（工具：Bash）

1. **Python依赖预检**：先验证所需Python库是否已安装
   ```bash
   python3 -c "import pandas; import matplotlib; import chardet; import tabulate" 2>&1
   ```
   - 任一模块缺失 → 提示用户执行 `pip install pandas matplotlib chardet numpy tabulate` 后重试，终止当前流程
   - 全部就绪 → 进入下一步
2. **路径校验**：确认 `file_path` 存在且后缀为 `.csv`
3. **大文件保护**：使用 `wc -c` 与 `du -h` 检查文件大小
   - 文件 > 100MB → 输出警告，询问用户是否继续（建议改用分块读取）
   - 文件 = 0 字节 → 终止并报错
4. **编码检测**：依次尝试 UTF-8、UTF-8-SIG、GBK、GB18030
   ```bash
   python3 -c "
   import chardet
   with open('FILE_PATH', 'rb') as f:
       raw = f.read(65536)
   print(chardet.detect(raw))
   " 2>/dev/null || python3 -c "
   for enc in ['utf-8','utf-8-sig','gbk','gb18030']:
       try:
           open('FILE_PATH', encoding=enc).read(4096); print(enc); break
       except UnicodeDecodeError: continue
   "
   ```

> **失败处理**：四种编码均无法解码时，提示用户手动指定编码或转换文件。

### 第二步：基础结构探查（工具：Bash）

执行 pandas 探查脚本，输出表结构概览：

```bash
python3 << 'PY'
import pandas as pd
df = pd.read_csv("FILE_PATH", encoding="DETECTED_ENC", nrows=5)
print("列名：", list(df.columns))
print("前5行预览：")
print(df.to_string())
PY
```

记录信息：
- 总列数、列名（支持中文）
- 列数据类型推断
- 是否存在表头

> **失败处理**：分隔符非逗号（如 `;`、`\t`）时，自动尝试 `sep=None, engine='python'` 让 pandas 自动嗅探。

### 第三步：统计分析（工具：Bash）

执行完整统计分析脚本：

```bash
python3 << 'PY'
import pandas as pd
import numpy as np

df = pd.read_csv("FILE_PATH", encoding="DETECTED_ENC")

# 基础信息
print(f"行数：{len(df)}")
print(f"列数：{len(df.columns)}")
print(f"内存占用：{df.memory_usage(deep=True).sum()/1024/1024:.2f} MB")

# 缺失值统计
miss = df.isnull().sum()
miss_pct = (miss / len(df) * 100).round(2)
print("\n缺失值统计：")
print(pd.DataFrame({"缺失数": miss, "缺失率(%)": miss_pct}))

# 数值列描述统计
num_cols = df.select_dtypes(include=[np.number]).columns
if len(num_cols) > 0:
    print("\n数值列统计：")
    print(df[num_cols].describe().round(3))

# 类别列分布（前10）
cat_cols = df.select_dtypes(include=["object"]).columns
for c in cat_cols[:5]:
    print(f"\n[{c}] 唯一值数：{df[c].nunique()}")
    print(df[c].value_counts().head(5))
PY
```

> **失败处理**：数据量过大导致 OOM 时，改用 `dtype` 指定与 `usecols` 限制读取列。

### 第四步：生成可视化图表（工具：Bash / Write）

生成图表并保存到与CSV同目录的 `summary_charts/` 子目录：

```bash
python3 << 'PY'
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import os, platform

# === 中文字体配置（避免乱码） ===
# 优先级：SimHei → Microsoft YaHei → Noto Sans CJK SC → PingFang SC → Arial Unicode MS
candidates = [
    "SimHei", "Microsoft YaHei", "Noto Sans CJK SC",
    "Source Han Sans CN", "PingFang SC", "Arial Unicode MS",
    "WenQuanYi Zen Hei",
]
available = {f.name for f in fm.fontManager.ttflist}
chosen = next((c for c in candidates if c in available), "DejaVu Sans")
plt.rcParams["font.sans-serif"] = [chosen]
plt.rcParams["axes.unicode_minus"] = False
print(f"使用字体：{chosen}")

df = pd.read_csv("FILE_PATH", encoding="DETECTED_ENC")
out_dir = os.path.join(os.path.dirname("FILE_PATH"), "summary_charts")
os.makedirs(out_dir, exist_ok=True)

# 图1：缺失值热力柱状图
miss = df.isnull().sum()
if miss.sum() > 0:
    fig, ax = plt.subplots(figsize=(10, 4))
    miss[miss > 0].sort_values(ascending=False).plot.bar(ax=ax, color="#E45756")
    ax.set_title("各列缺失值数量")
    ax.set_ylabel("缺失数量")
    plt.tight_layout()
    plt.savefig(os.path.join(out_dir, "01_missing.png"), dpi=120)
    plt.close()

# 图2：数值列分布（直方图）
num_cols = df.select_dtypes(include="number").columns[:6]
if len(num_cols) > 0:
    n = len(num_cols)
    fig, axes = plt.subplots((n+2)//3, 3, figsize=(12, 3*((n+2)//3)))
    axes = axes.flatten() if n > 1 else [axes]
    for i, c in enumerate(num_cols):
        df[c].dropna().hist(ax=axes[i], bins=30, color="#4C78A8")
        axes[i].set_title(f"{c} 分布")
    for j in range(len(num_cols), len(axes)):
        axes[j].axis("off")
    plt.tight_layout()
    plt.savefig(os.path.join(out_dir, "02_distribution.png"), dpi=120)
    plt.close()

# 图3：类别列Top分布
cat_cols = df.select_dtypes(include="object").columns[:3]
for i, c in enumerate(cat_cols):
    fig, ax = plt.subplots(figsize=(10, 4))
    df[c].value_counts().head(10).plot.barh(ax=ax, color="#54A24B")
    ax.set_title(f"{c} Top10 分布")
    ax.invert_yaxis()
    plt.tight_layout()
    plt.savefig(os.path.join(out_dir, f"03_category_{i+1}.png"), dpi=120)
    plt.close()

print(f"图表已保存至：{out_dir}")
PY
```

字体配置策略：
- macOS 默认可用 `PingFang SC`、`Arial Unicode MS`
- Linux 推荐安装 `fonts-noto-cjk`（`Noto Sans CJK SC`）
- Windows 默认可用 `SimHei`、`Microsoft YaHei`
- 找不到任何中文字体时，回退到 `DejaVu Sans` 并在报告中提示

> **失败处理**：matplotlib 不可用时，仅输出文本统计并在报告中说明"图表生成已跳过"。

### 第五步：生成Markdown报告（工具：Write）

将统计结果与图表引用写入 Markdown 文件，保存到 CSV 同目录的 `summary_report.md`：

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
| 1 | 订单编号 | object | 0 | 0.00% | 10000 |
| 2 | 金额 | float64 | 12 | 0.12% | 8930 |

## 三、数值列统计
| 列名 | mean | std | min | 25% | 50% | 75% | max |
|------|------|-----|-----|-----|-----|-----|-----|
| 金额 | 128.5 | 45.2 | 0.5 | 95.0 | 120.0 | 160.0 | 999.0 |

## 四、缺失值概览
![缺失值统计](summary_charts/01_missing.png)

## 五、数值分布
![数值列分布](summary_charts/02_distribution.png)

## 六、类别分布
![类别Top10](summary_charts/03_category_1.png)

## 七、数据质量提示
- ⚠️ 列「金额」存在 12 个缺失值，建议填充或剔除
- ⚠️ 列「地区」唯一值过多（>500），建议合并低频类别
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
- 文件 > 100MB 必须二次确认
- 不修改源CSV文件，所有产物写入新目录
- 不上传数据到任何外部服务
- 检测到疑似敏感列名（身份证、手机号、密码）时仅输出统计计数，不展示样例值

## 错误处理策略
- 编码无法识别 → 提示用户手动指定
- pandas/matplotlib 缺失 → 提示安装命令 `pip install pandas matplotlib chardet`
- 中文字体全部缺失 → 报告中明示并使用拉丁字体回退
