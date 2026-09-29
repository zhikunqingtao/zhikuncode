# tools/office-regression — R-12 离线回归基建

本目录是 R-12 的**离线（容器内 `--network none`）** Office/HTML 回归基础设施，构成是
「测试资产脚本 + 手写夹具 + 固定版本镜像」。

> 定位说明：XLSX/DOCX/PPTX 目前在产品代码中没有产品级"生成"入口。本目录的脚本
> （夹具生成、结构检查、固定文件校验）均为**测试资产**，用于固定期望值、暴露回归原因；
> 不代表产品已具备 Office 生成或修复能力，也不覆盖产品级导出链路。本目录**不参与
> CI 门禁**（按手动入口 `./run.sh` 运行）；研究来源评估不在本批范围。

## 四类离线回归

1. **OOXML 包结构**（`office_struct_check.py structure`）：检查原生构造 —— XLSX 公式 /
   跨表引用 / 数字格式，DOCX 原生标题样式与层级，PPTX 原生图表与内嵌工作簿；反例按
   reason code 精确归因（如 `XLSX_MISSING_FORMULA`、`DOCX_HEADING_STYLE_MISSING`、
   `PPTX_CHART_FLATTENED_IMAGE`）。
2. **XLSX 重算值**（`office_struct_check.py values`）：LibreOffice 实算后的导出必须与独立
   编写的期望值一致（检查重算结果，不读公式缓存值）。
3. **PDF 导出文本 / 页数**（`office_struct_check.py pdf`）：LibreOffice 导出 PDF 后经
   poppler（`pdftotext` / `pdfinfo`）校验必需文本与页数。
4. **HTML 固定夹具**（`fixedfile_check.py`）：`fixtures/html/`（正例、资源失效反例、
   键盘不可操作反例）由 `fixtures/HTML-SHA256SUMS` 清单固定；篡改 / 缺失 / 多余文件
   一律按 reason code 报出。

## 单入口：`./run.sh`

```bash
./run.sh                                # 构建 pinned 镜像 → docker run --network none 跑完整 pytest 套件
R12_SKIP_BUILD=1 ./run.sh               # 复用已存在的镜像
OFFICE_REGRESSION_OUT=DIR ./run.sh      # 覆盖证据输出目录（须在仓库外）
R12_PYTEST_ARGS='...' ./run.sh          # 追加 pytest 参数
```

- 退出码 = pytest 退出码（0 = 全部通过）。
- 失败语义：docker CLI / daemon 缺失、pinned base image 或镜像构建失败 → 输出显式
  `R12 ERROR: ...` 并以非零码退出（**BLOCKED，不得当作通过**）；套件内的 skip
  **不计为通过**，由 pytest 套件约定处理（本 README 不展开用例细节）。
- 容器约束：`docker run --rm --network none`；仓库只读挂载（`/work:ro`），证据单独
  挂载到 `/out`。

## pins 与镜像身份

- `pins.env` 记录完整工具链版本（LibreOffice、Noto CJK 字体、poppler、Python 测试
  依赖、base image 与 digest）：Dockerfile 构建期读取它安装全部 apt/pip 版本，
  `requirements.lock` 是 pins 的 pip 镜像。唯一例外是 `FROM` 行的 base image digest
  无法使用构建变量，需同时写在 Dockerfile 中——改动 digest 时两处必须同步。
  Dockerfile 构建期还会做 `soffice` / `pdftotext` / CJK 字体自检。
- 每次运行把镜像身份写入 `${OUT_DIR}/image-identity.txt`（image tag/id、base image 与
  digest、repo root、构建时间）；连同 `pytest.log`、`listing.txt` 构成证据集合。
- 预检用例（`tests/test_preflight.py`）断言容器实际携带的版本与 pins 一致；版本不匹配、
  依赖缺失或超时一律报 BLOCKED/失败，不记通过。

## OUT_DIR（仓库外）约定

- 默认 `${TMPDIR:-/tmp}/office-regression-out`，用 `OFFICE_REGRESSION_OUT` 覆盖。
- **必须位于仓库之外**：容器内仓库为只读挂载，`OUT_DIR` 映射到 `/out`；所有产物只写
  OUT_DIR，不污染工作树（`git status` 应保持干净）。

## 脚本（测试资产，stdlib only、Python 3.9 兼容）

| 脚本 | 定位 |
| --- | --- |
| `scripts/office_fixture_gen.py` | 确定性生成 XLSX/DOCX/PPTX 正反例夹具与 `specs/*.json` 期望（固定 zip 时间戳/排序，重复运行字节一致） |
| `scripts/office_struct_check.py` | `structure` / `values` / `pdf` 三模式检查；exit 0 健康、2 命中 reason code、1 用法/IO 错误 |
| `scripts/fixedfile_check.py` | SHA256 清单生成 / 校验（`gen` / `verify`）；reason code：`FIXEDFILE_MISMATCH`、`FIXEDFILE_MISSING`、`FIXEDFILE_EXTRA`、`FIXEDFILE_MANIFEST_INVALID` |

## pytest 套件

- 套件位于 `tests/`，通过 `./run.sh` 在容器内运行；本 README 不描述其内部用例。
- 另有 Java 侧轻量宿主机测试
  `backend/src/test/java/com/aicodeassistant/tool/impl/OfficeToolchainExecutionTest.java`，
  经真实 BashTool → ManagedProcessRunner 执行本目录的生成与结构检查脚本，不依赖
  LibreOffice（LibreOffice 实算只在容器套件里覆盖）。