package com.promenar.nexara.data.local.db

import android.database.sqlite.SQLiteDatabase
import java.util.Locale

/** 所有业务表合同均由已提交的导出 schema 生成，不从测试源集加载数据。 */
internal fun SQLiteDatabase.hasCompleteCurrentRoomSchema(version: Int, identity: String?): Boolean {
    val expected = GeneratedCurrentRoomSchemas.schemas[version] ?: return false
    if (identity != expected.identityHash) return false
    return expected.tables.all { table ->
        val actualSql = rawQuery(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table.name),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return@all false
        val actualColumns = readColumns(table.name).associateBy { it.name }
        val expectedColumns = table.columns.map { column ->
            // FTS4 不在 table_info 中暴露声明类型与非空约束，下面另外核对完整建表声明。
            val visible = if (table.ftsCreateSql == null) column else column.copy(type = "", notNull = false)
            visible.normalized()
        }.associateBy { it.name }
        actualColumns == expectedColumns &&
            (table.ftsCreateSql == null || canonicalFtsSql(actualSql) == canonicalFtsSql(table.ftsCreateSql)) &&
            hasExpectedIndices(table) &&
            readForeignKeys(table.name).groupingBy { it }.eachCount() ==
                table.foreignKeys.groupingBy { it }.eachCount()
    }
}

private fun SQLiteDatabase.readColumns(table: String): List<ExportedRoomColumn> =
    rawQuery("PRAGMA table_info(${quotedIdentifier(table)})", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(ExportedRoomColumn(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                    primaryKeyPosition = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                    defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                ).normalized())
            }
        }
    }

private fun ExportedRoomColumn.normalized() = copy(
    type = type.trim().uppercase(Locale.ROOT),
    defaultValue = defaultValue?.let { value ->
        var tokens = sqlTokens(value)
        while (tokens.isEnclosedByParentheses()) tokens = tokens.subList(1, tokens.lastIndex)
        tokens.joinToString("\u0000") { "${it.kind}:${it.value}" }
    },
)

private fun SQLiteDatabase.hasExpectedIndices(table: ExportedRoomTable): Boolean {
    val actual = rawQuery("PRAGMA index_list(${quotedIdentifier(table.name)})", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val origin = cursor.getString(cursor.getColumnIndexOrThrow("origin"))
                // 主键位置由列合同校验；额外 UNIQUE 约束不能冒充导出的命名索引。
                if (origin == "pk") continue
                if (origin != "c") return false
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val sql = rawQuery(
                    "SELECT sql FROM sqlite_master WHERE type='index' AND name=?", arrayOf(name),
                ).use { index -> if (index.moveToFirst()) index.getString(0) else null } ?: return false
                val columns = rawQuery("PRAGMA index_xinfo(${quotedIdentifier(name)})", null).use { info ->
                    buildList {
                        while (info.moveToNext()) {
                            if (info.getInt(info.getColumnIndexOrThrow("key")) == 0) continue
                            add(Triple(
                                info.getInt(info.getColumnIndexOrThrow("seqno")),
                                info.getString(info.getColumnIndexOrThrow("name")),
                                if (info.getInt(info.getColumnIndexOrThrow("desc")) == 0) "ASC" else "DESC",
                            ))
                        }
                    }.sortedBy { it.first }
                }
                add(IndexShape(
                    name, cursor.getInt(cursor.getColumnIndexOrThrow("unique")) != 0,
                    columns.map { it.second }, columns.map { it.third },
                    cursor.getInt(cursor.getColumnIndexOrThrow("partial")) != 0, indexPredicate(sql),
                ))
            }
        }
    }
    val expected = table.indices.map { index ->
        val predicate = indexPredicate(index.createSql)
        IndexShape(
            index.name, index.unique, index.columns, index.orders.map { it.uppercase(Locale.ROOT) },
            predicate.isNotEmpty(), predicate,
        )
    }
    return actual.associateBy { it.name } == expected.associateBy { it.name }
}

private fun SQLiteDatabase.readForeignKeys(table: String): List<ExportedRoomForeignKey> =
    rawQuery("PRAGMA foreign_key_list(${quotedIdentifier(table)})", null).use { cursor ->
        val rows = buildList {
            while (cursor.moveToNext()) {
                add(ForeignKeyRow(
                    cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("seq")),
                    cursor.getString(cursor.getColumnIndexOrThrow("table")),
                    cursor.getString(cursor.getColumnIndexOrThrow("on_delete")).uppercase(Locale.ROOT),
                    cursor.getString(cursor.getColumnIndexOrThrow("on_update")).uppercase(Locale.ROOT),
                    cursor.getString(cursor.getColumnIndexOrThrow("from")),
                    cursor.getString(cursor.getColumnIndexOrThrow("to")),
                ))
            }
        }
        rows.groupBy { it.id }.values.map { group ->
            val ordered = group.sortedBy { it.sequence }
            val first = ordered.first()
            ExportedRoomForeignKey(
                first.table, first.onDelete, first.onUpdate,
                ordered.map { it.column }, ordered.map { it.referencedColumn },
            )
        }
    }

private fun quotedIdentifier(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

private fun canonicalFtsSql(sql: String): List<SqlToken> {
    val tokens = sqlTokens(sql).toMutableList()
    if (tokens.take(6).map { it.value } == listOf("create", "virtual", "table", "if", "not", "exists")) {
        tokens.subList(3, 6).clear()
    }
    return tokens.map { it.copy(quoted = false) }
}

private fun indexPredicate(sql: String): List<SqlToken> {
    val tokens = sqlTokens(sql)
    var depth = 0
    tokens.forEachIndexed { index, token ->
        if (depth == 0 && token.kind == TokenKind.WORD && !token.quoted && token.value == "where") {
            return tokens.drop(index + 1).map { it.copy(quoted = false) }
        }
        if (token.isSymbol("(")) depth++
        if (token.isSymbol(")")) depth--
    }
    return emptyList()
}

private fun List<SqlToken>.isEnclosedByParentheses(): Boolean {
    if (size < 2 || !first().isSymbol("(") || !last().isSymbol(")")) return false
    var depth = 0
    forEachIndexed { index, token ->
        if (token.isSymbol("(")) depth++
        if (token.isSymbol(")")) depth--
        if (depth == 0 && index < lastIndex) return false
    }
    return depth == 0
}

/** 保留字面量大小写与引号边界，避免把字符串或引号内的 WHERE 当作部分索引条件。 */
private fun sqlTokens(sql: String): List<SqlToken> = buildList {
    var offset = 0
    while (offset < sql.length) {
        val character = sql[offset]
        when {
            character.isWhitespace() -> offset++
            sql.startsWith("--", offset) -> {
                val end = sql.indexOf('\n', offset + 2)
                offset = if (end < 0) sql.length else end + 1
            }
            sql.startsWith("/*", offset) -> {
                val end = sql.indexOf("*/", offset + 2)
                check(end >= 0) { "数据库结构 SQL 注释未闭合" }
                offset = end + 2
            }
            character == '\'' || character == '"' || character.code == 96 || character == '[' -> {
                val closing = if (character == '[') ']' else character
                val value = StringBuilder()
                var closed = false
                offset++
                while (offset < sql.length) {
                    val next = sql[offset++]
                    if (next == closing) {
                        if (offset < sql.length && sql[offset] == closing && character != '[') {
                            value.append(closing)
                            offset++
                        } else {
                            closed = true
                            break
                        }
                    } else value.append(next)
                }
                check(closed) { "数据库结构 SQL 引号未闭合" }
                val literal = character == '\''
                add(SqlToken(
                    if (literal) TokenKind.LITERAL else TokenKind.WORD,
                    if (literal) value.toString() else value.toString().lowercase(Locale.ROOT),
                    quoted = !literal,
                ))
            }
            character.isLetterOrDigit() || character == '_' || character == '$' -> {
                val start = offset++
                while (offset < sql.length &&
                    (sql[offset].isLetterOrDigit() || sql[offset] == '_' || sql[offset] == '$')
                ) offset++
                add(SqlToken(TokenKind.WORD, sql.substring(start, offset).lowercase(Locale.ROOT)))
            }
            else -> {
                add(SqlToken(TokenKind.SYMBOL, character.toString()))
                offset++
            }
        }
    }
    if (lastOrNull()?.isSymbol(";") == true) removeAt(lastIndex)
}

internal data class ExportedRoomSchema(val identityHash: String, val tables: List<ExportedRoomTable>)

internal data class ExportedRoomTable(
    val name: String,
    val columns: List<ExportedRoomColumn>,
    val indices: List<ExportedRoomIndex>,
    val foreignKeys: List<ExportedRoomForeignKey>,
    val ftsCreateSql: String?,
)

internal data class ExportedRoomColumn(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKeyPosition: Int,
    val defaultValue: String?,
)

internal data class ExportedRoomIndex(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
    val orders: List<String>,
    val createSql: String,
)

internal data class ExportedRoomForeignKey(
    val table: String,
    val onDelete: String,
    val onUpdate: String,
    val columns: List<String>,
    val referencedColumns: List<String?>,
)

private data class IndexShape(
    val name: String,
    val unique: Boolean,
    val columns: List<String?>,
    val orders: List<String>,
    val partial: Boolean,
    val predicate: List<SqlToken>,
)

private data class ForeignKeyRow(
    val id: Int,
    val sequence: Int,
    val table: String,
    val onDelete: String,
    val onUpdate: String,
    val column: String,
    val referencedColumn: String?,
)

private enum class TokenKind { WORD, LITERAL, SYMBOL }
private data class SqlToken(val kind: TokenKind, val value: String, val quoted: Boolean = false) {
    fun isSymbol(symbol: String) = kind == TokenKind.SYMBOL && value == symbol
}
