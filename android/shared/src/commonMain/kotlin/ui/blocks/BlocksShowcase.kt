package ui.blocks

import androidx.compose.runtime.Composable
import com.eightfs.lumigather.data.CountState
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import theme.AppTheme
import theme.LumigatherTheme
import theme.ThemeMode

/**
 * 业务块总览屏：用「真实数据形状的假数据」把三块各渲染一遍，供人工核对。
 *
 * 它同时是一道架构门禁：三块的验收标准是「能用假数据独立渲染」—— 如果哪一块偷偷依赖了仓库、
 * 发起了请求、或者绑定了全局状态，这个屏就渲染不出来（假数据里没有任何 ApiClient）。
 */
@Composable
fun BlocksShowcase(
    modifier: Modifier = Modifier,
    mode: ThemeMode = ThemeMode.Light,
) {
    LumigatherTheme(mode = mode) {
        val colors = AppTheme.colors
        val metrics = AppTheme.metrics

        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(metrics.spacingMd),
            verticalArrangement = Arrangement.spacedBy(metrics.spacingMd),
        ) {
            ShowcaseLabel("分类项：选中 / 未选 / 统计中 / 统计失败 / 未统计")
            Column(verticalArrangement = Arrangement.spacedBy(metrics.spacingXs)) {
                CategoryBlock(
                    name = "崩坏3",
                    color = CategoryColorFixture.HONKAI_3,
                    imageCount = 4218,
                    selected = true,
                    countState = CountState.READY,
                    onClick = {},
                )
                CategoryBlock(
                    name = "原神",
                    color = CategoryColorFixture.GENSHIN,
                    imageCount = 72651,
                    countState = CountState.READY,
                    onClick = {},
                )
                CategoryBlock(
                    name = "星穹铁道",
                    color = CategoryColorFixture.STAR_RAIL,
                    countState = CountState.LOADING,
                    onClick = {},
                )
                CategoryBlock(
                    name = "绝区零",
                    color = CategoryColorFixture.ZENLESS,
                    countState = CountState.FAILED,
                    onClick = {},
                )
                CategoryBlock(
                    name = "鸣潮",
                    color = CategoryColorFixture.WUTHERING,
                    countState = CountState.UNKNOWN,
                    onClick = {},
                )
            }

            ShowcaseLabel("图片格子：加载成功 / 加载中 / 加载失败（失败只影响本格）")
            Row(horizontalArrangement = Arrangement.spacedBy(metrics.spacingXs)) {
                ImageCellBlock(
                    imageUrl = SAMPLE_MEDIA_URL,
                    name = "loading-logo.f0c3ebe.png",
                    sizeText = "2.4 MB",
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )
                ImageCellBlock(
                    imageUrl = SAMPLE_MEDIA_URL,
                    name = "banner.png",
                    stateOverride = ImageCellState.Loading,
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )
                ImageCellBlock(
                    imageUrl = SAMPLE_BROKEN_URL,
                    name = "broken.png",
                    stateOverride = ImageCellState.Failed,
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )
            }

            ShowcaseLabel("详情信息区：默认停在「信息」页签，可点「操作」切换")
            DetailInfoBlock(
                image = SAMPLE_IMAGE,
                categoryName = "崩坏3",
                onDownload = {},
                onShare = {},
            )
        }
    }
}

/** 分组小标题：与组件总览屏保持同一种排版节奏（微字号 + 弱色）。 */
@Composable
private fun ShowcaseLabel(text: String) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    Text(text = text, color = colors.textFaint, fontSize = metrics.fontMicro)
}
