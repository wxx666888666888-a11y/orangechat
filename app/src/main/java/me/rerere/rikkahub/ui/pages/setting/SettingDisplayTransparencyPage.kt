package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun SettingDisplayTransparencyPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var ds by remember(settings) { mutableStateOf(settings.displaySetting) }
    fun update(s: DisplaySetting) {
        ds = s
        vm.updateSettings(settings.copy(displaySetting = s))
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("透明度与毛玻璃美化") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("橘瓣原版透明度（完全保留）") },
                ) {
                    item(
                        headlineContent = { Text("聊天气泡透明度") },
                        supportingContent = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Slider(value = ds.chatBubbleTransparency, onValueChange = { update(ds.copy(chatBubbleTransparency = it)) }, valueRange = 0f..100f, modifier = Modifier.weight(1f))
                                Text("${ds.chatBubbleTransparency.roundToInt()}%")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("思维链透明度") },
                        supportingContent = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Slider(value = ds.thinkingChainTransparency, onValueChange = { update(ds.copy(thinkingChainTransparency = it)) }, valueRange = 0f..100f, modifier = Modifier.weight(1f))
                                Text("${ds.thinkingChainTransparency.roundToInt()}%")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("侧边栏元素透明度") },
                        supportingContent = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Slider(value = ds.drawerItemAlpha, onValueChange = { update(ds.copy(drawerItemAlpha = it)) }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
                                Text("${(ds.drawerItemAlpha * 100).roundToInt()}%")
                            }
                        }
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("多形态毛玻璃美化（KE / 北极星切换）") },
                ) {
                    val modes = listOf(0 to "橘瓣原版", 1 to "KE清透毛玻璃", 2 to "北极星柔光磨砂")
                    item(
                        headlineContent = { Text("聊天气泡美化形态") },
                        supportingContent = {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                modes.forEachIndexed { i, (mode, label) ->
                                    SegmentedButton(
                                        selected = ds.bubbleGlassStyle == mode,
                                        onClick = { update(ds.copy(bubbleGlassStyle = mode)) },
                                        shape = SegmentedButtonDefaults.itemShape(i, modes.size)
                                    ) { Text(label) }
                                }
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("底部输入框与面板毛玻璃模糊") },
                        supportingContent = { Text("开启后底部输入栏与加号面板呈现高斯毛玻璃效果") },
                        trailingContent = {
                            Switch(checked = ds.enableBlurEffect, onCheckedChange = { update(ds.copy(enableBlurEffect = it)) })
                        }
                    )
                    if (ds.bubbleGlassStyle != 0) {
                        item(
                            headlineContent = { Text("气泡毛玻璃模糊强度") },
                            supportingContent = {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Slider(value = ds.bubbleBlurRadius, onValueChange = { update(ds.copy(bubbleBlurRadius = it)) }, valueRange = 2f..40f, modifier = Modifier.weight(1f))
                                    Text("${ds.bubbleBlurRadius.roundToInt()}dp")
                                }
                            }
                        )
                        item(
                            headlineContent = { Text("气泡高光边框粗细") },
                            supportingContent = {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Slider(value = ds.bubbleBorderWidth, onValueChange = { update(ds.copy(bubbleBorderWidth = it)) }, valueRange = 0f..2.5f, modifier = Modifier.weight(1f))
                                    Text("${String.format("%.1f", ds.bubbleBorderWidth)}dp")
                                }
                            }
                        )
                        item(
                            headlineContent = { Text("气泡边框不透明度") },
                            supportingContent = {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Slider(value = ds.bubbleBorderAlpha, onValueChange = { update(ds.copy(bubbleBorderAlpha = it)) }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
                                    Text("${(ds.bubbleBorderAlpha * 100).roundToInt()}%")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
