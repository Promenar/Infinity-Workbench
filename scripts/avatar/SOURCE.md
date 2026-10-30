# Material Symbols Rounded 头像符号来源

- 上游仓库：<https://github.com/google/material-design-icons>
- 固定提交：`10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`
- 上游日期：2026-10-23；来源与提交由工作包提供。
- 许可：Apache-2.0，Google LLC。许可全文随应用存放于 `native-ui/app/src/main/assets/licenses/material-symbols-LICENSE.txt`。
- 原始可变字体文件名：`MaterialSymbolsRounded[FILL,GRAD,opsz,wght].ttf`；码位表与许可均取自同一固定提交。
- 工作包输入文件名：`MaterialSymbolsRounded.ttf`、`MaterialSymbolsRounded.codepoints`、`LICENSE`。

| 输入文件 | SHA-256 |
| --- | --- |
| MaterialSymbolsRounded.ttf | `7e74e36533ccee12c2b81e75bfcf3ccf123d3bb7e7e46d65d94445f23db0c97c` |
| MaterialSymbolsRounded.codepoints | `a7404d9e5a1b63f496cf05ace45d386075886b5a7f123fba9f29527cb6006fde` |
| LICENSE | `58d1e17ffe5109a7ae296caafcadfdbe6a7d176f0bc4ab01e12a689b0499d8bd` |

人工精选源为 `scripts/avatar/avatar_symbols.tsv`，`popular=1` 表示常用。字形按分类顺序、同类 TSV 行序排列。生成器先按码位子集化，再固定轴 `FILL=1`、`wght=500`、`GRAD=0`、`opsz=24`；移除 GSUB 连字，保留 `.notdef` 和 name 表版权/许可条目。上游仅含版权项时补入许可名称与许可链接。

在仓库根运行，`SOURCE` 指已核验上述哈希的源文件所在目录，不需要纳入约 15 MB 的源字体：

```sh
python3 scripts/avatar/build_avatar_symbols.py \
  --font "$SOURCE/MaterialSymbolsRounded.ttf" \
  --codepoints "$SOURCE/MaterialSymbolsRounded.codepoints" \
  --tsv scripts/avatar/avatar_symbols.tsv \
  --output-dir native-ui/app/src/main
python3 -m unittest discover -s scripts/avatar -p 'test_*.py' -v
```

输出为 `native-ui/app/src/main/res/font/infinity_avatar_symbols.ttf` 和 `native-ui/app/src/main/java/com/promenar/infinity/data/avatar/AvatarSymbolCatalogData.kt`。运行时仅加载静态字体资源与内存目录，不读取 TSV 或 assets 目录。
