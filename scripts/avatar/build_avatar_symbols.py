#!/usr/bin/env python3
"""从人工精选源生成静态头像字体和 Kotlin 内存目录。"""
import argparse
import csv
import json
from pathlib import Path
import re
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

CATEGORIES = ('GENERAL', 'CODING', 'WRITING', 'LEARNING', 'WORK', 'CREATIVE', 'LIFE', 'NATURE', 'CHARACTERS')
AXES = {'FILL': 1, 'wght': 500, 'GRAD': 0, 'opsz': 24}
REQUIRED = {'auto_awesome', 'code', 'edit_note', 'smart_toy', 'psychology', 'star', 'local_fire_department',
            'lightbulb', 'bolt', 'track_changes', 'translate', 'all_inclusive', 'terminal', 'ink_pen'}
REGENERATE = ('python3 scripts/avatar/build_avatar_symbols.py --font "$SOURCE/MaterialSymbolsRounded.ttf" '
              '--codepoints "$SOURCE/MaterialSymbolsRounded.codepoints" '
              '--tsv scripts/avatar/avatar_symbols.tsv --output-dir native-ui/app/src/main')


def read_selection(path, codepoints):
    with Path(path).open(encoding='utf-8', newline='') as handle:
        reader = csv.DictReader(handle, delimiter='\t')
        if reader.fieldnames != ['category', 'name', 'label_zh', 'label_en', 'keywords', 'popular']:
            raise ValueError('TSV 列名不符合契约')
        rows = list(reader)
    names = set()
    points = set()
    for row in rows:
        name = row['name']
        if row['category'] not in CATEGORIES or not re.fullmatch('[a-z0-9_]+', name):
            raise ValueError(f'分类或字形名无效: {name}')
        if name not in codepoints or name in names or codepoints[name] in points:
            raise ValueError(f'未知或重复字形/码位: {name}')
        if row['popular'] not in ('0', '1') or any(not row[k].strip() for k in ('label_zh', 'label_en', 'keywords')):
            raise ValueError(f'标签或常用标识无效: {name}')
        names.add(name)
        points.add(codepoints[name])
    if not 380 <= len(rows) <= 420 or not REQUIRED <= names:
        raise ValueError('目录数量或必含字形不符合契约')
    if any(sum(r['category'] == c for r in rows) < 30 for c in CATEGORIES):
        raise ValueError('每类至少需要 30 个字形')
    if not 16 <= sum(r['popular'] == '1' for r in rows) <= 24:
        raise ValueError('常用字形数量必须在 16 到 24 之间')
    return sorted(rows, key=lambda r: CATEGORIES.index(r['category']))


def kotlin_string(value):
    return json.dumps(value, ensure_ascii=False).replace('$', '\\$')


def build(font_path, codepoints_path, tsv_path, output):
    codepoints = {}
    for line in Path(codepoints_path).read_text(encoding='utf-8').splitlines():
        name, point = line.split()
        if name in codepoints: raise ValueError(f'上游码位表重名字形: {name}')
        codepoints[name] = int(point, 16)
    rows = read_selection(tsv_path, codepoints)
    options = subset.Options()
    options.notdef_glyph = True
    options.notdef_outline = True
    options.layout_features = []
    options.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
    options.name_legacy = True
    options.name_languages = ['*']
    font = TTFont(font_path, recalcTimestamp=False)
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=[codepoints[r['name']] for r in rows])
    # 必须先子集化，再固定轴；避免对全量可变字体实例化。
    subsetter.subset(font)
    font = instantiateVariableFont(font, AXES, inplace=True)
    if 'GSUB' in font: del font['GSUB']
    # 上游只有版权项时补全许可名称与原文入口，同时保留原有 name 记录。
    for name_id, value in ((13, 'Licensed under the Apache License, Version 2.0'),
                           (14, 'https://www.apache.org/licenses/LICENSE-2.0')):
        if not any(record.nameID == name_id for record in font['name'].names):
            font['name'].setName(value, name_id, 3, 1, 0x409)
    font.recalcTimestamp = False
    font_path_out = Path(output) / 'res/font/infinity_avatar_symbols.ttf'
    data_path = Path(output) / 'java/com/promenar/infinity/data/avatar/AvatarSymbolCatalogData.kt'
    font_path_out.parent.mkdir(parents=True, exist_ok=True)
    data_path.parent.mkdir(parents=True, exist_ok=True)
    font.save(font_path_out)
    with TTFont(font_path_out) as checked:
        cmap = checked.getBestCmap()
        if any(codepoints[r['name']] not in cmap for r in rows): raise ValueError('生成字体缺少选定码位')
        if 'fvar' in checked or 'GSUB' in checked or '.notdef' not in checked.getGlyphOrder():
            raise ValueError('生成字体不是保留 .notdef 的无连字静态字体')
        if not {0, 13, 14} <= {n.nameID for n in checked['name'].names}:
            raise ValueError('版权或许可条目丢失')
        glyph_count = len(checked.getGlyphOrder())
    lines = ['// 由脚本生成，勿手改。', '// 重新生成：' + REGENERATE,
             'package com.promenar.infinity.data.avatar', '',
             'internal val avatarSymbolCatalogData: List<AvatarSymbol> = listOf(']
    for row in rows:
        words = ', '.join(kotlin_string(v.strip()) for v in row['keywords'].split('|') if v.strip())
        fields = [kotlin_string(row['name']), f'0x{codepoints[row["name"]]:X}',
                  'AvatarSymbolCategory.' + row['category'], kotlin_string(row['label_zh']), kotlin_string(row['label_en']),
                  'listOf(' + words + ')']
        lines.append('    AvatarSymbol(' + ', '.join(fields) + '),')
    lines += [')', '', 'internal val avatarPopularSymbolNames: List<String> = listOf(']
    lines += ['    ' + kotlin_string(r['name']) + ',' for r in rows if r['popular'] == '1']
    lines += [')', '']
    data_path.write_text('\n'.join(lines), encoding='utf-8')
    print(f'symbols={len(rows)} glyphs={glyph_count} font_bytes={font_path_out.stat().st_size} data_bytes={data_path.stat().st_size}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', required=True, type=Path)
    parser.add_argument('--codepoints', required=True, type=Path)
    parser.add_argument('--tsv', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path, help='Android main 源集目录')
    args = parser.parse_args()
    try: build(args.font, args.codepoints, args.tsv, args.output_dir)
    except (ValueError, OSError) as error: parser.exit(1, f'{error}\n')


if __name__ == '__main__': main()
