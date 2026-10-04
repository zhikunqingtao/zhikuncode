# 字体许可说明 — brush-kaiti（演示悠然小楷）

## 基本信息

| 项目 | 内容 |
|---|---|
| 字体名称 | 演示悠然小楷（英文族名：slideyouran / slideyouran Regular） |
| 字体版本 | Version 1.000（字体文件内嵌 name 表记录） |
| 字稿作者 | 孟祥媛（书法专业学生） |
| 出品/发布 | 秋叶PPT 联合 Keynote研究所（keynoteart）出品发布 |
| 版权字串 | Copyright © 2020 by keynoteart x mengxiangyuan x QiuyePPT. All rights reserved. |
| 字符覆盖 | 简体中文字楷体，约 6763+ 字符（GB2312 全集及常用字） |

## 许可类型

**免费可商用**。演示悠然小楷由秋叶PPT 与 Keynote研究所面向全社会联合发布，官方明确宣布**全渠道免费可商用**（个人及企业商业用途均可，无需付费、无需告知原作者）。其官方授权的 GitHub 开源转发渠道（maoken-fonts/slidefont，持有演示字体委托书）以 **SIL Open Font License 1.1（OFL-1.1）** 发布该字体文件。

## 许可证据（可核查）

1. 官方发布声明（微信公众号，标题即「免费可商用字体」）：
   https://mp.weixin.qq.com/s/Q1lAIre4yJ-Zlf2CD82EPA （《来了！我们推出了第二款免费可商用字体！》，秋叶PPT × Keynote研究所）
2. 官方字荐报告（知乎专栏，明示"免费可商用字体"）：
   https://zhuanlan.zhihu.com/p/698594057
3. GitHub 授权开源转发仓（含 OFL.txt 与授权委托书 `documentation/委託書.pdf`，README 声明："本字体系列以 SIL 开源字型授权，版本 1.1 发布 / These fonts are licensed under SIL Open Font License, version 1.1."）：
   https://github.com/maoken-fonts/slidefont
4. 免费商用字体收录仓 wordshub/free-font（标注该字体"商免"并给出授权出处）：
   https://github.com/wordshub/free-font

## 下载源

- 实际下载源（npm 包，内含完整 `演示悠然小楷.ttf`，12.05 MB）：
  https://registry.npmmirror.com/@fontpkg/slideyouran/-/slideyouran-1.0.0.tgz
  （包 @fontpkg/slideyouran@1.0.0，文件 SHA 前验证为 TrueType Font data，与 maoken-fonts/slidefont 仓中 `fonts/Slideyouran-Regular.ttf` 为同一字体文件）
- 首选源说明：因本机网络访问 raw.githubusercontent.com / codeload.github.com / cdn.jsdelivr.net 均被重置，GitHub 直链不可用，故改用 npmmirror 的同一字体包；字体身份经 fontTools 读取内嵌 name/版权表核验一致。

## 下载日期

2026-10-03

## 仓内交付文件与子集化

| 文件 | 说明 |
|---|---|
| `brush-kaiti.woff2` | 子集化 Web 字体（约 3.0 MB，3885 字符：ASCII + 常用中英文标点 + demo 用字 + GB2312 一级字库 3755 字；fontTools pyftsubset 生成，去 hinting，保留全部 layout features）——**本仓内唯一字体文件** |
| `OFL.txt` | SIL Open Font License 1.1 许可全文（OFL 要求随字体附带） |
| `LICENSE-字体说明.md` | 本说明 |

> 全量母版 `brush-kaiti.ttf`（12.6 MB，6763+ 字符）与子集字符清单 `subset-chars.txt` **不入仓**（体积原因），如需复现子集化，按下述命令自 npmmirror 包 `@fontpkg/slideyouran` 获取同一 ttf 后执行；字符清单可联系主题维护者或按 demo 页面用字重新收集。

子集化命令（可复现）：
```
python3 -m fontTools.subset brush-kaiti.ttf --output-file=brush-kaiti.woff2 \
  --flavor=woff2 --text-file=<subset-chars.txt + GB2312 一级字库> \
  --layout-features='*' --no-hinting --desubroutinize
```
