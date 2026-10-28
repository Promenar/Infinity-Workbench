package com.promenar.infinity.data.state

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test

class StateBoardEngineTest {
    private fun number(value: Int) = JsonPrimitive(value)
    private fun field(key: String = "hp", type: String = "number", value: JsonElement = number(80)) =
        StateField(key, key, type, value)
    private fun board(vararg fields: StateField) = StateBoardEngine.define(null, "冒险", fields.toList(), 1)
    private fun meter(current: Int, max: Int = 100) = buildJsonObject { put("current", current); put("max", max) }

    @Test fun `重定义保留同key现值与更新时间允许改名排序增删`() {
        val old = board(field(), field("place", "text", JsonPrimitive("山洞")), field("removed"))
        val updated = StateBoardEngine.define(old, "旅程", listOf(
            field("place", "text", JsonPrimitive("错误初值")).copy(label = "位置"),
            field().copy(label = "生命值", value = number(0)), field("new"),
        ), 2)
        assertThat(updated.title).isEqualTo("旅程")
        assertThat(updated.fields.map { it.key }).containsExactly("place", "hp", "new").inOrder()
        assertThat(updated.fields[1].value).isEqualTo(number(80))
        assertThat(updated.fields[1].updatedAt).isEqualTo(1)
        assertThat(updated.fields[0].label).isEqualTo("位置")
    }
    @Test fun `同key类型冲突必须明确拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            StateBoardEngine.define(board(field()), "面板", listOf(field(type = "text", value = JsonPrimitive("x"))), 2)
        }
    }
    @Test fun `批量更新支持设值增减列表追加移除与清空并保留前后值`() {
        val old = board(field(), field("bag", "list", JsonArray(listOf(JsonPrimitive("绳子")))))
        val result = StateBoardEngine.update(old, listOf(
            StateOperation("hp", "increment", number(-15)),
            StateOperation("bag", "append", JsonArray(listOf(JsonPrimitive("火把")))),
            StateOperation("bag", "remove", JsonArray(listOf(JsonPrimitive("绳子")))),
        ), 10, user = true)
        assertThat(result.board.fields[0].value).isEqualTo(number(65))
        assertThat(result.changes.first().before).isEqualTo(number(80))
        assertThat(result.changes.first().after).isEqualTo(number(65))
        assertThat(result.board.fields[1].value).isEqualTo(JsonArray(listOf(JsonPrimitive("火把"))))
        assertThat(result.board.manualKeys).containsExactly("hp", "bag")
        assertThat(StateBoardEngine.update(result.board, listOf(StateOperation("bag", "clear")), 11).board.fields[1].value).isEqualTo(JsonArray(emptyList()))
    }
    @Test fun `列表初值非空字符串保存为单项数组`() {
        val initial = board(field("bag", "list", JsonPrimitive("绳子")))
        assertThat(initial.fields.single().value).isEqualTo(JsonArray(listOf(JsonPrimitive("绳子"))))
    }
    @Test fun `列表设值追加移除接受单字符串并返回数组前后值`() {
        val initial = board(field("bag", "list", JsonArray(listOf(JsonPrimitive("绳子")))))
        val set = StateBoardEngine.update(initial, listOf(StateOperation("bag", "set", JsonPrimitive("火把"))), 2)
        assertThat(set.board.fields.single().value).isEqualTo(JsonArray(listOf(JsonPrimitive("火把"))))
        assertThat(set.changes.single().before).isEqualTo(JsonArray(listOf(JsonPrimitive("绳子"))))
        assertThat(set.changes.single().after).isEqualTo(JsonArray(listOf(JsonPrimitive("火把"))))
        val appended = StateBoardEngine.update(set.board, listOf(StateOperation("bag", "append", JsonPrimitive("绳子"))), 3)
        assertThat(appended.board.fields.single().value).isEqualTo(JsonArray(listOf(JsonPrimitive("火把"), JsonPrimitive("绳子"))))
        val removed = StateBoardEngine.update(appended.board, listOf(StateOperation("bag", "remove", JsonPrimitive("火把"))), 4)
        assertThat(removed.board.fields.single().value).isEqualTo(JsonArray(listOf(JsonPrimitive("绳子"))))
    }
    @Test fun `列表单字符串仍限制非空与单项长度且失败保持原值`() {
        val initial = board(field("bag", "list", JsonArray(listOf(JsonPrimitive("绳子")))))
        val longest = "字".repeat(200)
        val updated = StateBoardEngine.update(initial, listOf(StateOperation("bag", "set", JsonPrimitive(longest))), 2)
        assertThat(updated.board.fields.single().value).isEqualTo(JsonArray(listOf(JsonPrimitive(longest))))
        for (invalid in listOf("", " \n\t", "字".repeat(201))) {
            assertThrows(IllegalArgumentException::class.java) { board(field("bag", "list", JsonPrimitive(invalid))) }
            for (op in listOf("set", "append", "remove")) {
                assertThrows(IllegalArgumentException::class.java) {
                    StateBoardEngine.update(initial, listOf(StateOperation("bag", op, JsonPrimitive(invalid))), 2)
                }
            }
        }
        assertThat(initial.fields.single().value).isEqualTo(JsonArray(listOf(JsonPrimitive("绳子"))))
    }
    @Test fun `列表单字符串追加不能超过五十项并保持整批原子性`() {
        val initial = board(field(), field("bag", "list", JsonArray(List(50) { JsonPrimitive("item$it") })))
        assertThrows(IllegalArgumentException::class.java) {
            StateBoardEngine.update(initial, listOf(
                StateOperation("hp", "set", number(5)),
                StateOperation("bag", "append", JsonPrimitive("新物品")),
            ), 2)
        }
        assertThat(initial.fields.first().value).isEqualTo(number(80))
        assertThat(initial.fields.last().value.jsonArray).hasSize(50)
    }
    @Test fun `进度条支持增减设值清空并限制范围`() {
        val old = board(field(type = "meter", value = meter(80)))
        val result = StateBoardEngine.update(old, listOf(StateOperation("hp", "increment", number(-15))), 2)
        assertThat(result.board.fields.single().value).isEqualTo(meter(65))
        assertThat(StateBoardEngine.update(old, listOf(StateOperation("hp", "clear")), 2).board.fields.single().value).isEqualTo(meter(0))
        for (invalid in listOf(meter(-1), meter(101), meter(1, 0))) {
            assertThrows(IllegalArgumentException::class.java) { board(field(type = "meter", value = invalid)) }
        }
    }
    @Test fun `错误操作和越限必须拒绝且原面板不变`() {
        val old = board(field())
        for (operation in listOf(StateOperation("missing", "set", number(1)), StateOperation("hp", "append", JsonArray(emptyList())), StateOperation("hp", "unknown"), StateOperation("hp", "set", JsonPrimitive("12")))) {
            assertThrows(IllegalArgumentException::class.java) { StateBoardEngine.update(old, listOf(operation), 2) }
        }
        assertThat(old.fields.single().value).isEqualTo(number(80))
    }
    @Test fun `限制字段数量文字长度列表大小和数值有效性`() {
        for (fields in listOf(
            (0..StateBoardEngine.MAX_FIELDS).map { field("k$it") },
            listOf(field(), field()), listOf(field("bad key")),
            listOf(field(type = "text", value = JsonPrimitive("x".repeat(StateBoardEngine.MAX_TEXT + 1)))),
            listOf(field(type = "list", value = JsonArray(List(StateBoardEngine.MAX_ITEMS + 1) { JsonPrimitive("x") }))),
            listOf(field(value = JsonPrimitive(Double.POSITIVE_INFINITY))),
        )) assertThrows(IllegalArgumentException::class.java) { board(*fields.toTypedArray()) }
        assertThrows(IllegalArgumentException::class.java) { StateBoardEngine.define(null, "x".repeat(81), listOf(field()), 1) }
    }

    @Test fun `文字设值数值清空与正向增量更新时间正确`() {
        val initial = board(field(), field("place", "text", JsonPrimitive("山洞")))
        val updated = StateBoardEngine.update(initial, listOf(
            StateOperation("hp", "increment", number(10)),
            StateOperation("place", "set", JsonPrimitive("山顶")),
        ), 99)
        assertThat(updated.board.fields.first().value).isEqualTo(number(90))
        assertThat(updated.board.fields.last().value).isEqualTo(JsonPrimitive("山顶"))
        assertThat(updated.board.fields.last().updatedAt).isEqualTo(99)
        val cleared = StateBoardEngine.update(updated.board, listOf(StateOperation("hp", "clear"), StateOperation("place", "clear")), 100)
        assertThat(cleared.board.fields.map { it.value }).containsExactly(number(0), JsonPrimitive("")).inOrder()
    }
    @Test fun `边界内最大值可用超出字段元数据与列表条目上限被拒绝`() {
        board(field(type = "text", value = JsonPrimitive("x".repeat(StateBoardEngine.MAX_TEXT))))
        board(field(type = "list", value = JsonArray(List(StateBoardEngine.MAX_ITEMS) { JsonPrimitive("x".repeat(StateBoardEngine.MAX_ITEM_TEXT)) })))
        val invalid = listOf(
            field().copy(label = "x".repeat(StateBoardEngine.MAX_LABEL + 1)),
            field().copy(unit = "x".repeat(17)),
            field(type = "text", value = JsonPrimitive("")).copy(unit = "字"),
            field(type = "list", value = JsonArray(listOf(JsonPrimitive("x".repeat(StateBoardEngine.MAX_ITEM_TEXT + 1))))),
            field(type = "list", value = JsonArray(listOf(JsonPrimitive("")))),
            field(type = "text", value = JsonPrimitive(2)),
            field(value = JsonPrimitive(1e13)),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { board(it) } }
        assertThrows(IllegalArgumentException::class.java) { StateBoardEngine.define(null, "", listOf(field()), 1) }
        assertThrows(IllegalArgumentException::class.java) { board() }
    }
    @Test fun `追加导致越限和过长批量必须整体拒绝`() {
        val initial = board(field("bag", "list", JsonArray(List(50) { JsonPrimitive("item$it") })))
        assertThrows(IllegalArgumentException::class.java) {
            StateBoardEngine.update(initial, listOf(StateOperation("bag", "append", JsonArray(listOf(JsonPrimitive("new"))))), 2)
        }
        assertThrows(IllegalArgumentException::class.java) { StateBoardEngine.update(initial, emptyList(), 2) }
        assertThrows(IllegalArgumentException::class.java) { StateBoardEngine.update(initial, List(51) { StateOperation("bag", "clear") }, 2) }
        assertThat(initial.fields.single().value.jsonArray).hasSize(50)
    }
}
