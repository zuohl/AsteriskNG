// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(ExperimentalScrollBarApi::class)

package features.proxy.server.editor

import features.subscription.DefaultSubscriptionGroupId
import app.LocalAppStateStore
import app.LocalAppServices
import app.LocalIsWideScreen
import app.LocalNavigator
import app.collectAppState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.R
import features.proxy.server.model.Custom
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.ProxyServerValidationIssue
import features.proxy.server.model.isCustomProxyServer
import features.proxy.server.model.isCompositeProxyServer
import features.proxy.server.usecase.ProxyServerCopyTextResult
import features.proxy.server.usecase.proxyServerCopyText
import ui.components.BackNavigationIcon
import ui.components.NavigationIcon
import kotlinx.coroutines.launch
import app.navigation.ProxyServerEditResult
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Ok
import ui.layout.AdaptiveTopAppBar
import features.proxy.server.display.displayName
import features.proxy.server.display.displayNameWithGroup
import features.proxy.server.display.displayNameById
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageScrollModifiers
import features.proxy.server.validation.rememberProxyServerValidationMessageResolver
import ui.clipboard.setPlainText
import androidx.compose.runtime.getValue
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProxyServerPage(
    padding: PaddingValues,
    ps: ProxyServer<*>,
    serverId: Int? = null,
    groupId: Int? = null,
    returnGroupId: Int? = null,
    resultKey: String? = null,
) {
    val isWideScreen = LocalIsWideScreen.current
    val appState by LocalAppStateStore.current.collectAppState()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val navigator = LocalNavigator.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val validationMessageOf = rememberProxyServerValidationMessageResolver()
    val copiedMessage = stringResource(R.string.common_copied)
    val unsupportedMessage = stringResource(R.string.common_unsupported)
    val invalidConfigMessage = stringResource(R.string.proxy_server_config_invalid)
    val fullValidationWarningTitle = stringResource(R.string.proxy_editor_full_validation_warning_title)
    val fullValidationWarningSummary = stringResource(R.string.proxy_editor_full_validation_warning_summary)
    val continueSaveMessage = stringResource(R.string.proxy_editor_continue_save)
    val returnEditMessage = stringResource(R.string.proxy_editor_return_edit)
    val unknownGroupName = stringResource(R.string.common_unknown_group)
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val allGroupsLabel = stringResource(R.string.proxy_editor_strategy_group_all_groups)
    val defaultProxyServerTemplate = stringResource(R.string.routing_default_proxy_server)

    val psEdit = remember(ps) {
        ps.editableCopy()
    }
    var pendingSaveIssues by remember { mutableStateOf<List<ProxyServerValidationIssue>>(emptyList()) }
    fun saveProxyServer() {
        if (resultKey != null && serverId != null) {
            navigator.setResult(
                resultKey,
                ProxyServerEditResult(
                    serverId = serverId,
                    server = psEdit,
                    groupId = groupId,
                    returnGroupId = returnGroupId,
                ),
            )
        } else {
            ps.update(psEdit)
            navigator.pop()
        }
    }
    val groupOptions = remember(appState.subscriptionGroups, allGroupsLabel, defaultGroupName) {
        listOf(ProxyServerEditorGroupOption(null, allGroupsLabel)) +
            appState.subscriptionGroups
                .filter { group -> group.enabled || group.builtIn || group.id == DefaultSubscriptionGroupId }
                .map { group ->
                    ProxyServerEditorGroupOption(
                        id = group.id,
                        label = group.displayName(defaultGroupName).ifBlank { defaultGroupName },
                    )
                }
    }
    val memberOptions = remember(appState.proxyServers, appState.subscriptionGroups, serverId, unknownGroupName, defaultGroupName) {
        val groupNames = appState.subscriptionGroups.displayNameById(defaultGroupName)
        appState.proxyServers
            .filter { server ->
                server.id != serverId &&
                    !server.server.isCompositeProxyServer() &&
                    !server.server.isCustomProxyServer()
            }
            .map { server ->
                ProxyServerEditorMemberOption(
                    id = server.id,
                    label = server.displayNameWithGroup(
                        defaultProxyServerTemplate = defaultProxyServerTemplate,
                        groupNames = groupNames,
                        unknownGroupName = unknownGroupName,
                    ),
                )
            }
    }
    val editorOptions = remember(groupOptions, memberOptions) {
        ProxyServerEditorOptions(
            groupOptions = groupOptions,
            memberOptions = memberOptions,
        )
    }
    val title = psEdit.editorTitle()

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = title,
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    BackNavigationIcon(
                        onClick = { navigator.pop() },
                    )
                },
                actions = {
                    NavigationIcon(
                        onClick = {
                            scope.launch {
                                val basicIssues = psEdit.validateBasic()
                                if (basicIssues.isNotEmpty()) {
                                    tipNotifier.show(validationMessageOf(basicIssues.first()))
                                    return@launch
                                }
                                when (
                                    val result = psEdit.proxyServerCopyText(
                                        context = context,
                                        appState = appState,
                                        serverId = serverId,
                                        groupId = groupId,
                                    )
                                ) {
                                    is ProxyServerCopyTextResult.Success -> {
                                        clipboard.setPlainText(result.text)
                                        tipNotifier.show(copiedMessage)
                                    }

                                    ProxyServerCopyTextResult.Unsupported -> {
                                        tipNotifier.show(unsupportedMessage)
                                    }

                                    ProxyServerCopyTextResult.InvalidConfig -> {
                                        tipNotifier.show(invalidConfigMessage)
                                    }
                                }
                            }
                        },
                        imageVector = MiuixIcons.Copy,
                    )
                    NavigationIcon(
                        onClick = {
                            val basicIssues = psEdit.validateBasic()
                            if (basicIssues.isNotEmpty()) {
                                scope.launch {
                                    tipNotifier.show(validationMessageOf(basicIssues.first()))
                                }
                            } else {
                                val fullIssues = psEdit.validateFull()
                                if (fullIssues.isNotEmpty()) {
                                    pendingSaveIssues = fullIssues
                                } else {
                                    saveProxyServer()
                                }
                            }
                        },
                        imageVector = MiuixIcons.Ok,
                    )
                },
            )
        },
    ) { innerPadding ->
        val lazyListState = rememberLazyListState()
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        if (psEdit is Custom) {
            CustomProxyServerEditor(
                customEdit = psEdit,
                contentPadding = contentPadding,
            )
            return@Scaffold
        }
        Box(
            modifier = Modifier.imePadding(),
        ) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.pageScrollModifiers(
                    topAppBarScrollBehavior,
                ),
                contentPadding = contentPadding,
            ) {
                proxyServerEditorContent(psEdit, editorOptions)
            }
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(lazyListState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                trackPadding = contentPadding,
            )
        }
    }

    ProxyServerFullValidationWarningDialog(
        show = pendingSaveIssues.isNotEmpty(),
        title = fullValidationWarningTitle,
        summary = fullValidationWarningSummary,
        issueMessages = pendingSaveIssues.map(validationMessageOf),
        returnEditText = returnEditMessage,
        continueSaveText = continueSaveMessage,
        onDismissRequest = { pendingSaveIssues = emptyList() },
        onContinueSave = {
            pendingSaveIssues = emptyList()
            saveProxyServer()
        },
    )
}

@Composable
private fun ProxyServerFullValidationWarningDialog(
    show: Boolean,
    title: String,
    summary: String,
    issueMessages: List<String>,
    returnEditText: String,
    continueSaveText: String,
    onDismissRequest: () -> Unit,
    onContinueSave: () -> Unit,
) {
    if (!show) return

    val warningColor = MiuixTheme.colorScheme.error
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(warningColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "!",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = warningColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        text = title,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(warningColor.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = summary,
                            modifier = Modifier.fillMaxWidth(),
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium,
                            color = warningColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(warningColor.copy(alpha = 0.06f))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        issueMessages.distinct().forEachIndexed { index, message ->
                            if (index > 0) {
                                Spacer(Modifier.height(10.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 7.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(warningColor),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = message,
                                    modifier = Modifier.weight(1f),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            text = returnEditText,
                            onClick = onDismissRequest,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = continueSaveText,
                            onClick = onContinueSave,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
