# 公开发行库结构夹具

这些夹具只包含 schema SQL 与公开 Room identity，不包含任何真实用户数据。测试自行填入合成哨兵。

2026-09-22，在新建专用 Android API 35 / arm64-v8a 模拟器中安装官方 `v0.1-beta`，启动并停止应用后提取 schema；随后使用 `adb install -r` 覆盖为官方 `v0.2-beta`，启动并停止后提取当前库 schema。两个包均通过签名验证，证书 SHA-256 为 `00be4cdd8378aafbd70ebc43e971523791cc07deead631e06dcf155deeee3802`。

| 文件 | 来源 APK SHA-256 | 实测数据库 |
| --- | --- | --- |
| public-v17.json | `0dfe33b280af97b94c27203b9e66691ff8e41af237777a93d21d2b27907b9291` | `nexara.db` / v17 / `3311ec5f07e8df42c02fd09163c49f6e` |
| public-v2.json | `59d641b6b8f04a15bc9f4ce064a7a116df3def4efabf8231feb408612246fe9a` | `nexara_v2.db` / v2 / `7777303c63145d5bbb9b161b38f94495` |

来源为 `https://github.com/Promenar/Nexara/releases/tag/v0.1-beta` 与 `https://github.com/Promenar/Nexara/releases/tag/v0.2-beta`。v0.2 的实际公开数据库是 v2；v5 是后续源码候选的结构，不能把两者混称。

JSON 的 `statements` 保留每条完整建表、索引和 trigger SQL。Android 测试逐条执行，不按分号拆分 trigger。SQLite 自建 `sqlite_*`、`android_metadata` 及 FTS4 shadow 表不重复建表；虚表语句负责创建 shadow 表。初始化完成后按 `roomIdentity` 写入 room_master_table 的 42 行并设置 `userVersion`。同名 SQL 文件仅用于人工查看；测试以 JSON 为准。
