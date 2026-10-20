package ca.ilianokokoro.umihi.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.proxy.ProxyController
import ca.ilianokokoro.umihi.music.ui.components.FadingStatusBarWrapper
import ca.ilianokokoro.umihi.music.ui.screens.settings.components.BooleanSettingItem
import ca.ilianokokoro.umihi.music.ui.screens.settings.components.SettingSpacer
import ca.ilianokokoro.umihi.music.ui.screens.settings.components.SettingsItem
import kotlinx.coroutines.launch

/**
 * Subscription-driven proxy settings.
 *
 * Kept intentionally to one screen and three controls (toggle, subscription box, node list) so
 * the app still feels like the lightweight player it was before.
 */
@Composable
fun ProxySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ProxyController.status.collectAsStateWithLifecycle()

    var subscriptionInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(status.subscriptionUrl) {
        if (subscriptionInput.isBlank()) {
            subscriptionInput = status.subscriptionUrl
        }
    }

    FadingStatusBarWrapper { statusBarHeight ->
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp)
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = statusBarHeight,
                    bottom = Constants.Ui.SCROLLABLE_BOTTOM_PADDING +
                        paddingValues.calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = "网络代理",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                item {
                    BooleanSettingItem(
                        title = "启用代理",
                        subtitle = statusSummary(status),
                        leadingIcon = Icons.Outlined.CloudQueue,
                        value = status.enabled,
                        onToggle = { enabled ->
                            scope.launch { ProxyController.setEnabled(context, enabled) }
                        }
                    )
                }

                item { SettingSpacer() }

                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = subscriptionInput,
                            onValueChange = { subscriptionInput = it },
                            label = { Text("订阅链接") },
                            placeholder = { Text("https://example.com/api/v1/client/subscribe?token=…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    ProxyController.refreshSubscription(context, subscriptionInput)
                                }
                            },
                            enabled = !status.busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Text(
                                when {
                                    status.busy -> "处理中…"
                                    status.nodes.isEmpty() -> "导入订阅"
                                    else -> "刷新节点"
                                }
                            )
                        }
                    }
                }

                status.error?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                status.notice?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                if (status.nodes.isNotEmpty()) {
                    item {
                        Text(
                            text = "节点 · ${status.nodes.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                    }

                    // Indexed so a duplicate node key can never crash the list.
                    items(count = status.nodes.size) { index ->
                        val node = status.nodes[index]
                        SettingsItem(
                            title = node.displayName,
                            subtitle = "${node.type.uppercase()} · ${node.server}:${node.port}",
                            leadingIcon = Icons.Outlined.Language,
                            trailingIcon = if (node.key == status.selectedKey) {
                                Icons.Outlined.Check
                            } else {
                                null
                            },
                            onClick = {
                                scope.launch { ProxyController.selectNode(context, node) }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun statusSummary(status: ProxyController.Status): String = when {
    status.busy -> "正在启动内核…"
    status.error != null -> "启动失败，详情见下方"
    status.running -> "已连接 127.0.0.1:${status.port} · ${status.nodeName.orEmpty()}"
    status.enabled -> "已启用，但当前没有可用节点"
    else -> "关闭时直连，国内网络无法访问 YouTube Music"
}
