#!/usr/bin/env python3
"""
流水线驱动脚本 - 读取 pipeline.json，为每轮生成具体的 prompt 文件。
生成到 prompts/ 目录，每个文件可直接复制粘贴到 Qoder 执行。
"""

import json
import os
import textwrap

WORKSPACE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PIPELINE_PATH = os.path.join(WORKSPACE, 'pipeline.json')
SPEC_SECTIONS_DIR = os.path.join(WORKSPACE, 'spec_sections')
PROMPTS_DIR = os.path.join(WORKSPACE, 'prompts')

PROMPT_TEMPLATE = """\
# Round {round}: {name}

> 阶段: {phase_name} | 依赖: {depends_str} | SPEC: {spec_sections}

---

## 指令

请使用 `/implement-module` Skill，参照以下 SPEC 片段实现 **{name}** 模块。

### SPEC 片段文件

请读取以下文件作为实现参考：

{spec_files_list}

### 核心要求

{key_points_list}

### 已完成依赖

{depends_detail}

### 技术约束

- Java 21+ / Virtual Threads
- Spring Boot 3.3+
- 包结构按 §2.8.1（参考 spec_sections/ch02/s2.4_Java_后端包结构.md）
- 前端: React 18 + TypeScript + Vite + Zustand
- Python: FastAPI + asyncio

### 验收标准

{acceptance_list}

---

## 完成后

执行 `/verify-module` 校验本轮实现与 SPEC 的一致性：

```
/verify-module
模块: {name}
SPEC 片段: {spec_files_first}
章节: {spec_sections}
```
"""


def load_pipeline():
    with open(PIPELINE_PATH, 'r', encoding='utf-8') as f:
        return json.load(f)


def get_round_name(pipeline, round_num):
    """根据轮次号获取模块名"""
    for phase in pipeline['phases']:
        for task in phase['tasks']:
            if task['round'] == round_num:
                return task['name']
    return f"Round {round_num}"


def generate_prompt(task, phase_name, pipeline):
    """为单个 task 生成 prompt"""

    # SPEC 片段文件列表
    spec_files_list = '\n'.join(
        f'- `spec_sections/{f}`' for f in task['spec_files']
    ) if task['spec_files'] else '- (无特定片段，参照已完成模块代码)'

    # 关键设计点
    key_points = task.get('key_points', [])
    key_points_list = '\n'.join(
        f'{i+1}. {p}' for i, p in enumerate(key_points)
    ) if key_points else '- 参照 SPEC 片段中的设计要求'

    # 依赖
    depends_on = task.get('depends_on', [])
    depends_str = ', '.join(f'R{d}' for d in depends_on) if depends_on else '无'
    depends_detail = '\n'.join(
        f'- R{d}: {get_round_name(pipeline, d)}'
        for d in depends_on
    ) if depends_on else '- 无前序依赖'

    # 验收标准
    acceptance = task.get('acceptance', [])
    acceptance_list = '\n'.join(
        f'- [ ] {a}' for a in acceptance
    ) if acceptance else '- [ ] 编译通过\n- [ ] 基础测试通过'

    # 第一个 spec 文件
    spec_files_first = task['spec_files'][0] if task['spec_files'] else 'N/A'

    return PROMPT_TEMPLATE.format(
        round=task['round'],
        name=task['name'],
        phase_name=phase_name,
        depends_str=depends_str,
        spec_sections=task.get('spec_sections', ''),
        spec_files_list=spec_files_list,
        key_points_list=key_points_list,
        depends_detail=depends_detail,
        acceptance_list=acceptance_list,
        spec_files_first=spec_files_first,
    )


def main():
    pipeline = load_pipeline()
    os.makedirs(PROMPTS_DIR, exist_ok=True)

    total = 0
    for phase in pipeline['phases']:
        phase_name = phase['name']
        for task in phase['tasks']:
            round_num = task['round']
            prompt = generate_prompt(task, phase_name, pipeline)

            filename = f'round_{round_num:02d}_{task["name"].replace(" ", "_").replace("/", "_")}.md'
            filepath = os.path.join(PROMPTS_DIR, filename)

            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(prompt)

            print(f'  [{phase_name}] Round {round_num:2d}: {filename}')
            total += 1

    # 生成执行清单
    checklist_path = os.path.join(PROMPTS_DIR, '_CHECKLIST.md')
    with open(checklist_path, 'w', encoding='utf-8') as f:
        f.write('# SPEC 执行清单\n\n')
        f.write(f'总轮次: {total}\n\n')
        for phase in pipeline['phases']:
            f.write(f'## {phase["name"]}\n\n')
            for task in phase['tasks']:
                r = task['round']
                depends = ', '.join(f'R{d}' for d in task.get('depends_on', [])) or '无'
                f.write(f'- [ ] **R{r:02d}**: {task["name"]} (依赖: {depends})\n')
            f.write('\n')

    print(f'\n共生成 {total} 个 prompt 文件 + 1 个执行清单')
    print(f'输出目录: {PROMPTS_DIR}')
    print(f'执行清单: {checklist_path}')


if __name__ == '__main__':
    main()
