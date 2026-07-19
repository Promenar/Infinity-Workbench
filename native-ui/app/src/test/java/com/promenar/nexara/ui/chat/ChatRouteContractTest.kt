package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ChatRouteContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }
    private val chatSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt",
    )
    private val routeSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/chat/ChatRoute.kt",
    )
    private val navSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/navigation/NavGraph.kt",
    )
    private val pipelineSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt",
    )

    @Test
    fun `route owns injected dependencies and content does not resolve application or view model`() {
        val source = routeSource.readText()
        val content = chatSource.readText().substringAfter("fun ChatScreenContent(")

        assertThat(source).contains("data class ChatRouteDependencies")
        assertThat(source).contains("fun ChatRoute(")
        assertThat(content).doesNotContain("viewModel(")
        assertThat(content).doesNotContain("NexaraApplication")
        assertThat(content).doesNotContain("rememberLauncherForActivityResult")
        assertThat(content).doesNotContain("copyToClipboard(")
    }

    @Test
    fun `session settings receives the route view model instance`() {
        val source = projectRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/SessionSettingsSheet.kt",
        ).readText()

        assertThat(source).contains("chatViewModel: ChatViewModel")
        assertThat(source.substringAfter("fun SessionSettingsSheet(")).doesNotContain(
            "ChatViewModel.factory",
        )
    }

    @Test
    fun `chat destination is injectable without mutable registry`() {
        val source = navSource.readText()

        assertThat(source).contains("chatDestination: ChatDestination")
        assertThat(source).contains("chatDestination(sessionId")
        assertThat(source).doesNotContain("ChatDestinationRegistry")
    }

    @Test
    fun `route离开时释放常亮标志且权限流程可跨重建`() {
        val source = routeSource.readText()

        assertThat(source).contains("DisposableEffect(activity)")
        assertThat(source).contains("activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)")
        assertThat(source).contains("permissionInFlightTaskId by rememberSaveable")
        assertThat(source).contains("NotificationPermissionDisposition.WAIT_FOR_SYSTEM_RESULT")
        assertThat(source).contains("notificationPermissionResultTaskId(")
    }

    @Test
    fun `overlay和图片使用可保存表示且不保存Uri对象`() {
        val source = routeSource.readText()

        assertThat(source).contains("selectedImageUriStrings by rememberSaveable")
        assertThat(source).contains("activeOverlayToken by rememberSaveable")
        assertThat(source).contains("queuedOverlayToken by rememberSaveable")
        assertThat(source).doesNotContain("selectedImageUris by rememberSaveable")
    }

    @Test
    fun `route统一构造显示名 map 并注入 screen state`() {
        val route = routeSource.readText()
        val screen = chatSource.readText()
        val mapConstruction = route
            .substringAfter("val modelDisplayNames = remember(")
            .substringBefore("val snackbarHostState")

        assertThat(route).contains(
            "ProviderManager.getInstance().providerModels.collectAsStateWithLifecycle()",
        )
        assertThat(mapConstruction).contains(
            "providerModels, uiState.session?.modelId, uiState.messages",
        )
        assertThat(mapConstruction).contains("resolveModelDisplayNames(")
        assertThat(mapConstruction).contains("sessionModelId = uiState.session?.modelId")
        assertThat(mapConstruction).contains(
            "messageModelIds = uiState.messages.map { it.modelId }",
        )
        val screenState = route.substringAfter("state = ChatScreenState(")
            .substringBefore("actions = ChatScreenActions(")
        assertThat(screenState).contains("modelDisplayNames = modelDisplayNames")
        assertThat(screen).contains("val modelDisplayNames: Map<String, String> = emptyMap()")
    }

    @Test
    fun `输入区与历史尾注只消费 route 注入的同一显示名 map`() {
        val screen = chatSource.readText()
        val pipeline = pipelineSource.readText()
        val content = screen.substringAfter("fun ChatScreenContent(")
        val pipelineFunction = pipeline.substringAfter("fun PipelineBubble(")
        val pipelineCall = content.substringAfter("PipelineBubble(")
            .substringBefore("if (compressionState.isCompressing")
        val inputModel = content.substringAfter("val modelDisplayName =")
            .substringBefore("if (selectedImageUris.isNotEmpty())")
        val pipelineLookup = pipelineFunction
            .substringAfter("if (!lastMsg.modelId.isNullOrBlank())")
            .substringBefore("Text(")
        val compactInputModel = inputModel.replace(Regex("\\s+"), "")
        val compactPipelineLookup = pipelineLookup.replace(Regex("\\s+"), "")

        assertThat(pipelineCall).contains("modelDisplayNames = state.modelDisplayNames")
        assertThat(compactInputModel).contains(
            "uiState.session?.modelId?.let(state.modelDisplayNames::get).orEmpty()",
        )
        assertThat(inputModel).contains("ChatInputTopBar(")
        assertThat(inputModel).contains("modelName = modelDisplayName")
        assertThat(pipelineFunction).contains(
            "modelDisplayNames: Map<String, String> = emptyMap()",
        )
        assertThat(compactPipelineLookup).contains("modelDisplayNames[lastMsg.modelId]")
        assertThat(compactPipelineLookup).contains(
            "?:lastMsg.modelId?.substringAfter(\"::\",lastMsg.modelId).orEmpty()",
        )
        assertThat(content).doesNotContain("findModelSpec(")
        assertThat(content).doesNotContain("resolveModelDisplayName(")
        assertThat(pipelineFunction).doesNotContain("findModelSpec(")
        assertThat(pipelineFunction).doesNotContain("resolveModelDisplayName(")
    }
}
