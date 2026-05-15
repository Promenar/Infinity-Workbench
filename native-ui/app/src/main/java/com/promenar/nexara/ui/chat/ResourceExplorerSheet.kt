package com.promenar.nexara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.ui.chat.components.FilesPanel
import com.promenar.nexara.ui.chat.components.RecycleBinPanel
import com.promenar.nexara.ui.chat.components.ResourceExplorerViewModel
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.launch

@Composable
fun ResourceExplorerSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    sessionId: String,
    viewModel: ResourceExplorerViewModel = viewModel(
        factory = ResourceExplorerViewModel.factory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val recycleBinCount by viewModel.recycleBinCount.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    NexaraBottomSheet(
        show = show,
        onDismiss = onDismiss,
        title = "资源管理器"
    ) {
        Column {
            NexaraSearchBar(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = "搜索文件..."
            )

            Spacer(modifier = Modifier.height(12.dp))

            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = NexaraColors.Primary,
                divider = {
                    HorizontalDivider(color = NexaraColors.OutlineVariant)
                },
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        val pos = tabPositions[pagerState.currentPage]
                        Box(
                            Modifier
                                .tabIndicatorOffset(pos)
                                .height(3.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(NexaraColors.Primary)
                        )
                    }
                }
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = { Text("文件", style = NexaraTypography.labelMedium) },
                    selectedContentColor = NexaraColors.Primary,
                    unselectedContentColor = NexaraColors.OnSurfaceVariant
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = {
                        Icon(
                            Icons.Rounded.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = {
                        Text(
                            if (recycleBinCount > 0) "回收站 ($recycleBinCount)" else "回收站",
                            style = NexaraTypography.labelMedium
                        )
                    },
                    selectedContentColor = NexaraColors.Primary,
                    unselectedContentColor = NexaraColors.OnSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> FilesPanel(
                        workspaceRootUuid = viewModel.workspaceRootUuid,
                        workspaceRepo = viewModel.workspaceRepo,
                        searchQuery = searchQuery
                    )
                    1 -> RecycleBinPanel(
                        workspaceRootUuid = viewModel.workspaceRootUuid,
                        workspaceRepo = viewModel.workspaceRepo
                    )
                }
            }
        }
    }
}
