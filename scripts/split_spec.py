#!/usr/bin/env python3
"""
SPEC.md 自动拆分脚本
按 ## / ### 级标题将 SPEC.md 拆分为独立片段文件。
超过 MAX_SECTION_LINES 的子章节按 #### 进一步拆分。
"""

import os
import re
import json
import unicodedata

SPEC_PATH = os.path.join(os.path.dirname(__file__), '..', 'SPEC.md')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'spec_sections')
MAX_SECTION_LINES = 3000
CHANGELOG_END_LINE = 607  # 变更日志结束行号

# 章节名中文到拼音/英文的映射（用于文件名）
CH_NAME_MAP = {
    '1': '项目概述',
    '2': '系统架构设计',
    '3': 'P0核心模块',
    '4': 'P1增强模块',
    '5': '数据模型',
    '6': 'API接口设计',
    '7': '数据库设计',
    '8': '前端页面设计',
    '9': '安全设计',
    '10': '部署方案',
    '11': '开发路线图',
    '12': '附录',
}


def sanitize_filename(name: str) -> str:
    """清理文件名，去除特殊字符"""
    name = re.sub(r'[<>:"/\\|?*]', '', name)
    name = re.sub(r'\s+', '_', name.strip())
    name = re.sub(r'[（(].*?[）)]', '', name)  # 去除括号内容
    name = name.strip('_')
    return name[:80]  # 限制长度


def parse_sections(lines: list[str]):
    """
    解析 SPEC.md，返回结构化章节列表。
    每个章节: {level, number, title, start_line, end_line, lines_content}
    """
    sections = []
    current = None

    # 匹配 ## N. 或 ### N.N 或 #### N.N.N 标题
    h2_pattern = re.compile(r'^## (\d+)\.\s+(.*)')
    h3_pattern = re.compile(r'^### (\d+\.\d+[a-z]?)\s+(.*)')
    h4_pattern = re.compile(r'^#### (\d+\.\d+\.\d+[a-z]?(?:\.\d+)?)\s+(.*)')

    for i, line in enumerate(lines):
        line_num = i + 1  # 1-based

        h2_match = h2_pattern.match(line)
        h3_match = h3_pattern.match(line)

        if h2_match:
            # 关闭前一个 h2 节（及其最后一个子节）
            if current:
                current['end_line'] = line_num - 1
                if current['subsections']:
                    for sub in current['subsections']:
                        if sub['end_line'] is None:
                            sub['end_line'] = line_num - 1
                sections.append(current)
            ch_num = h2_match.group(1)
            title = h2_match.group(2).strip()
            current = {
                'level': 2,
                'number': ch_num,
                'title': title,
                'start_line': line_num,
                'end_line': None,
                'subsections': []
            }
        elif h3_match and current and current['level'] == 2:
            # 关闭前一个子节
            if current['subsections']:
                prev = current['subsections'][-1]
                if prev['end_line'] is None:
                    prev['end_line'] = line_num - 1
            sec_num = h3_match.group(1)
            title = h3_match.group(2).strip()
            current['subsections'].append({
                'level': 3,
                'number': sec_num,
                'title': title,
                'start_line': line_num,
                'end_line': None,
                'sub_subsections': []
            })

    # 关闭最后一个
    if current:
        current['end_line'] = len(lines)
        if current['subsections']:
            for sub in current['subsections']:
                if sub['end_line'] is None:
                    sub['end_line'] = current['end_line']
        sections.append(current)

    return sections


def find_h4_splits(lines: list[str], start: int, end: int):
    """在给定行范围内查找 #### 级标题，用于进一步拆分大节"""
    h4_pattern = re.compile(r'^####\s+(\d+\.\d+\.\d+[a-z]?(?:\.\d+[a-z]?)?)\s+(.*)')
    splits = []
    for i in range(start - 1, min(end, len(lines))):
        m = h4_pattern.match(lines[i])
        if m:
            splits.append({
                'number': m.group(1),
                'title': m.group(2).strip(),
                'start_line': i + 1
            })
    # 设置 end_line
    for j in range(len(splits) - 1):
        splits[j]['end_line'] = splits[j + 1]['start_line'] - 1
    if splits:
        splits[-1]['end_line'] = end
    return splits


def write_section(filepath: str, lines: list[str], start: int, end: int):
    """将指定行范围写入文件"""
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    content = ''.join(lines[start - 1:end])
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)
    line_count = end - start + 1
    return line_count


def split_spec():
    with open(SPEC_PATH, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    total_lines = len(lines)
    print(f"SPEC.md 总行数: {total_lines}")

    # 1. 变更日志
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    changelog_lines = write_section(
        os.path.join(OUTPUT_DIR, 'ch00_changelog.md'),
        lines, 1, CHANGELOG_END_LINE
    )
    print(f"  ch00_changelog.md: {changelog_lines} 行")

    # 2. 解析章节结构
    sections = parse_sections(lines)

    index = []  # 目录索引

    for sec in sections:
        ch_num = sec['number']
        ch_name = CH_NAME_MAP.get(ch_num, sec['title'])
        sec_line_count = sec['end_line'] - sec['start_line'] + 1

        # 判断是否需要子拆分
        needs_subsplit = sec_line_count > MAX_SECTION_LINES and sec['subsections']

        if needs_subsplit:
            # 创建章节目录
            ch_dir = os.path.join(OUTPUT_DIR, f'ch{ch_num.zfill(2)}')
            os.makedirs(ch_dir, exist_ok=True)

            # 写入章节头部（到第一个子节之前）
            if sec['subsections']:
                header_end = sec['subsections'][0]['start_line'] - 1
                if header_end >= sec['start_line']:
                    lc = write_section(
                        os.path.join(ch_dir, f'_header.md'),
                        lines, sec['start_line'], header_end
                    )
                    print(f"  ch{ch_num.zfill(2)}/_header.md: {lc} 行")
                    index.append({
                        'file': f'ch{ch_num.zfill(2)}/_header.md',
                        'section': f'§{ch_num}',
                        'title': ch_name + ' (头部)',
                        'lines': lc
                    })

            # 写入每个子节
            for sub in sec['subsections']:
                sub_num = sub['number']
                sub_title = sanitize_filename(sub['title'])
                sub_line_count = sub['end_line'] - sub['start_line'] + 1

                if sub_line_count > MAX_SECTION_LINES:
                    # 子节也太大，按 #### 进一步拆分
                    h4_splits = find_h4_splits(lines, sub['start_line'], sub['end_line'])
                    if h4_splits and len(h4_splits) > 1:
                        # 写入子节头部
                        sub_header_end = h4_splits[0]['start_line'] - 1
                        if sub_header_end >= sub['start_line']:
                            fname = f's{sub_num}_{sub_title}_header.md'
                            lc = write_section(
                                os.path.join(ch_dir, fname), lines,
                                sub['start_line'], sub_header_end
                            )
                            print(f"  ch{ch_num.zfill(2)}/{fname}: {lc} 行")
                            index.append({
                                'file': f'ch{ch_num.zfill(2)}/{fname}',
                                'section': f'§{sub_num}',
                                'title': sub['title'] + ' (头部)',
                                'lines': lc
                            })

                        # 写入每个 h4 子小节
                        for h4 in h4_splits:
                            h4_title = sanitize_filename(h4['title'])
                            fname = f's{h4["number"]}_{h4_title}.md'
                            lc = write_section(
                                os.path.join(ch_dir, fname), lines,
                                h4['start_line'], h4['end_line']
                            )
                            print(f"  ch{ch_num.zfill(2)}/{fname}: {lc} 行")
                            index.append({
                                'file': f'ch{ch_num.zfill(2)}/{fname}',
                                'section': f'§{h4["number"]}',
                                'title': h4['title'],
                                'lines': lc
                            })
                    else:
                        # 没有 h4 或太少，整体写入
                        fname = f's{sub_num}_{sub_title}.md'
                        lc = write_section(
                            os.path.join(ch_dir, fname), lines,
                            sub['start_line'], sub['end_line']
                        )
                        print(f"  ch{ch_num.zfill(2)}/{fname}: {lc} 行 (大节未进一步拆分)")
                        index.append({
                            'file': f'ch{ch_num.zfill(2)}/{fname}',
                            'section': f'§{sub_num}',
                            'title': sub['title'],
                            'lines': lc
                        })
                else:
                    fname = f's{sub_num}_{sub_title}.md'
                    lc = write_section(
                        os.path.join(ch_dir, fname), lines,
                        sub['start_line'], sub['end_line']
                    )
                    print(f"  ch{ch_num.zfill(2)}/{fname}: {lc} 行")
                    index.append({
                        'file': f'ch{ch_num.zfill(2)}/{fname}',
                        'section': f'§{sub_num}',
                        'title': sub['title'],
                        'lines': lc
                    })
        else:
            # 整体写入单文件
            fname = f'ch{ch_num.zfill(2)}_{sanitize_filename(ch_name)}.md'
            lc = write_section(
                os.path.join(OUTPUT_DIR, fname), lines,
                sec['start_line'], sec['end_line']
            )
            print(f"  {fname}: {lc} 行")
            index.append({
                'file': fname,
                'section': f'§{ch_num}',
                'title': ch_name,
                'lines': lc
            })

    # 写入索引文件
    index_path = os.path.join(OUTPUT_DIR, 'INDEX.json')
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False, indent=2)
    print(f"\n索引已写入: {index_path}")
    print(f"共生成 {len(index)} 个片段文件")

    return index


if __name__ == '__main__':
    split_spec()
