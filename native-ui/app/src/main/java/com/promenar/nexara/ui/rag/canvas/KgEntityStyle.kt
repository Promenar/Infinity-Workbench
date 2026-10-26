package com.promenar.nexara.ui.rag.canvas

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraKgEntityDark
import com.promenar.nexara.ui.theme.NexaraKgEntityLight
import com.promenar.nexara.ui.theme.nexaraDomainColors

/** 图谱实体类型：节点统一为圆形，类型只由颜色与内部图标区分。顺序与 [NexaraKgEntityDark] 对应。 */
enum class KgEntityKind(val icon: ImageVector, @StringRes val label: Int) {
    PERSON(Icons.Rounded.Person, R.string.kg_type_person),
    ORGANIZATION(Icons.Rounded.Groups, R.string.kg_type_organization),
    LOCATION(Icons.Rounded.Place, R.string.kg_type_location),
    EVENT(Icons.Rounded.Event, R.string.kg_type_event),
    ITEM(Icons.Rounded.Category, R.string.kg_type_item),
    CONCEPT(Icons.Rounded.Lightbulb, R.string.kg_type_concept),
    DOCUMENT(Icons.Rounded.Description, R.string.kg_type_document),
    OTHER(Icons.Rounded.Hub, R.string.kg_type_other);

    companion object {
        /** 抽取提示词约定的类型名；旧数据中的近义写法归并，未知类型归为“其它”。 */
        fun of(type: String): KgEntityKind = when (type.trim().lowercase()) {
            "person", "people", "character" -> PERSON
            "organization", "organisation", "org", "group", "company", "team" -> ORGANIZATION
            "location", "place", "city", "region" -> LOCATION
            "event" -> EVENT
            "item", "technology", "tool", "product", "object", "artifact" -> ITEM
            "concept" -> CONCEPT
            "document", "file" -> DOCUMENT
            else -> OTHER
        }
    }
}

@Composable
@ReadOnlyComposable
fun kgEntityPalette(): List<Color> =
    if (MaterialTheme.nexaraDomainColors.isDark) NexaraKgEntityDark else NexaraKgEntityLight

fun List<Color>.of(kind: KgEntityKind): Color = this[kind.ordinal]
