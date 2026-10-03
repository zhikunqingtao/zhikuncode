# tools/office-regression — R-12 离线回归基建

本目录是 R-12 的**离线（容器内 `--network none`）** Office/HTML 回归基础设施，构成是
「测试资产脚本 + 手写夹具 + 固定版本镜像」。

> 定位说明：XLSX/DOCX/PPTX 目前在产品代码中没有产品级"生成"入口。本目录的脚本
> （夹具生成、结构检查、固定文件校验）均为**测试资产**，用于固定期望值、暴露回归原因；
> 不代表产品已具备 Office 生成或修复能力，也不覆盖产品级导出链路。独立 CI 在相关
> 变更推送到 `main` 后运行本套件，也支持手动触发；研究来源评估不在本批范围。

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
R12_SKIP_BUILD=1 ./run.sh               # 复用已存在的镜像，仍须通过工具链契约校验
OFFICE_REGRESSION_OUT=DIR ./run.sh      # 本次证据目录（仓库外，不存在或为空）
R12_PYTEST_ARGS='...' ./run.sh          # 替换默认 pytest 参数；脚本仍自动附加 tests
```

- pytest 启动后返回其退出码；启动前的环境或契约校验失败同样返回非零码。
  自定义参数会替换默认的缓存禁用、超时等参数，筛选运行不代表完整套件通过。
- 失败语义：docker CLI / daemon 缺失、pinned base image 或镜像构建失败 → 输出显式
  `R12 ERROR: ...` 并以非零码退出（**BLOCKED，不得当作通过**）；套件内的 skip
  表示该用例未验证，需查看测试计数；不会仅因出现 skip 就判定整套失败。
- 容器约束：`docker run --rm --network none`；仓库只读挂载（`/work:ro`），证据单独
  挂载到 `/out`。
- 官方入口在同一容器中先比较仓库与镜像内的 `pins.env` / `requirements.lock` 契约，
  忽略注释等非实质差异；匹配后才运行 pytest，`-k` 等筛选不会跳过此校验。
  直接调用 pytest 的入口行为不变。

## pins 与镜像身份

- `pins.env` 记录完整工具链版本（LibreOffice、Noto CJK 字体、poppler、Python 测试
  依赖、base image 与 digest）：Dockerfile 构建期读取它安装全部 apt/pip 版本，
  `requirements.lock` 是 pins 的 pip 镜像。本仓库在 Dockerfile 的 `FROM` 行显式
  固定基础镜像 digest；调整时须同步 Dockerfile 与 `pins.env`。
  Dockerfile 构建期还会做 `soffice` / `pdftotext` / CJK 字体自检。
- 每次运行解析并记录镜像 ID，随后按该 ID 执行，避免 tag 在记录后变化。
  `${OUT_DIR}/image-identity.txt`、`pytest.log`、`listing.txt` 保存本次身份与执行证据；
  运行时间不作为镜像构建时间。即使设置 `R12_SKIP_BUILD=1`，镜像内契约仍须与仓库匹配。
- `toolchain-contract.json` 保存匹配结果和有效配置摘要；声明的基础镜像 digest 与实际
  运行镜像 ID 分开记录。启动器记录 HEAD、工作区状态、执行参数和退出结果；缺少结束
  记录的运行不能视为完成。异常遗留的目录不自动清理，应使用新的输出目录。
- 预检用例（`tests/test_preflight.py`）断言容器实际携带的版本与 pins 一致；版本不匹配、
  依赖缺失或超时一律报 BLOCKED/失败，不记通过。契约匹配不代替这些实际版本检查。
- HTML 使用固定镜像中的 Playwright Chromium；不将该结果表述为系统 Chrome 或其他
  平台已经验收。

## OUT_DIR（仓库外）约定

- 默认通过 `mktemp` 在 `/tmp` 下为每次运行创建独立目录，实际路径由启动日志打印。
- `OFFICE_REGRESSION_OUT` 仍表示**本次目录**，不是保存多次运行的父目录；可指定尚不
  存在或为空的目录。包含隐藏文件在内的非空目录，以及已被其他运行占用的目录会被拒绝，
  不复用或覆盖旧证据。再次运行时应指定新的目录，失败证据也保留供检查。
- **必须位于仓库之外**：容器内仓库为只读挂载，`OUT_DIR` 映射到 `/out`；所有产物只写
  本次 OUT_DIR，工具所需的临时文件可写容器内 `/tmp`，不污染工作树。

## 独立 CI

- `.github/workflows/office-regression.yml` 在 `main` push 的以下路径变更时运行：
  `tools/office-regression/**`、`python-service/src/services/browser_service.py` 和 workflow
  自身；也可通过 `workflow_dispatch` 手动运行。既有主 CI 的触发范围保持不变。
- 使用 `ubuntu-24.04`，将镜像构建并加载到 runner 本地，以 `R12_SKIP_BUILD=1` 复用该
  镜像运行完整套件，不筛选用例、不发布镜像；构建缓存与运行并发组均独立。
- job 上限 45 分钟，构建步骤上限 30 分钟，测试步骤上限 10 分钟；测试显式禁用 pytest
  缓存、设置超时并输出 JUnit XML。每次运行使用 runner 临时目录中的独立证据目录。
- 成败均尽力上传证据，保留 7 天；构建失败时查看 Actions 构建日志。失败不会被转成成功，
  main push 的运行用于提交后的回归反馈，不代表提交前的合并门禁或多平台验收。

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
