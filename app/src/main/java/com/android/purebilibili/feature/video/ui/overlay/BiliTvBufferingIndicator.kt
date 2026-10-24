package com.android.purebilibili.feature.video.ui.overlay

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.R
import com.android.purebilibili.core.ui.AdaptiveLoadingIndicator
import com.android.purebilibili.core.ui.components.AppText

/**
 * 播放器默认缓冲动画：小电视循环动画（res/raw/bili_tv_buffering.webp）。
 *
 * 动画源为单色位图，按 [tint] 染色，随主题主色联动（含动态取色与主题切换）。
 * API 28 以下系统没有动画 WebP 解码器，回退通用加载指示器，不改变两条材质路径。
 */
@Composable
internal fun BiliTvAnimatedBufferingIndicator(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        AdaptiveLoadingIndicator(modifier = modifier, size = size, color = tint)
        return
    }
    val context = LocalContext.current
    val model = remember(context) {
        "android.resource://${context.packageName}/${R.raw.bili_tv_buffering}"
    }
    AsyncImage(
        model = model,
        contentDescription = "加载中",
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/**
 * 居中缓冲展示：径向渐变暗色圆底 + 小电视动画 + 文案。
 *
 * 固定比例：动画 25dp、圆底四周 20dp 内边距、文案 12sp 白色；
 * 圆底渐变由 26% 黑过渡到透明，保证在任意画面亮度下的可读性。
 * 文案规则：已缓冲时长 > 0 时显示已缓冲到的位置，否则显示「加载中…」。
 */
@Composable
internal fun BiliTvBufferingIndicator(
    tint: Color,
    modifier: Modifier = Modifier,
    bufferedProvider: () -> Long = { 0L },
) {
    val bufferedMs = bufferedProvider().coerceAtLeast(0L)
    val label = if (bufferedMs > 0L) {
        com.android.purebilibili.core.util.FormatUtils.formatDuration(
            (bufferedMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    } else {
        "加载中..."
    }
    Box(
        modifier = modifier
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.26f), Color.Transparent),
                ),
                shape = CircleShape,
            )
            .padding(20.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BiliTvAnimatedBufferingIndicator(tint = tint, size = 25.dp)
            AppText(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
