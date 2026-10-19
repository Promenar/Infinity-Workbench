package com.promenar.nexara.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraSpacing

/** 穿透式目录导航栈中的一级；根目录不入栈。 */
data class FolderCrumb(
    val id: String,
    val name: String,
) : java.io.Serializable

/** 返回上级按钮加可点击的路径面包屑；知识库与会话工作区共用。 */
@Composable
fun FolderBreadcrumbsBar(
    folderStack: List<FolderCrumb>,
    rootTitle: String,
    onNavigateBack: () -> Unit,
    onNavigateToCrumb: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backButtonTag: String = "rag_folder_back_btn",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = NexaraSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folderStack.isNotEmpty()) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .size(36.dp)
                    .testTag(backButtonTag),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.rag_back_to_parent),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val allCrumbs = remember(folderStack, rootTitle) {
            listOf(FolderCrumb(id = "", name = rootTitle)) + folderStack
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            allCrumbs.forEachIndexed { index, crumb ->
                val isLast = index == allCrumbs.lastIndex
                Text(
                    text = crumb.name,
                    style = if (isLast) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                    color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isLast) {
                            onNavigateToCrumb(index)
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                if (!isLast) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/** 点击第 [index] 级面包屑（0 为根）后的导航栈。 */
fun List<FolderCrumb>.navigateToCrumb(index: Int): List<FolderCrumb> =
    if (index <= 0) emptyList() else take(index)
