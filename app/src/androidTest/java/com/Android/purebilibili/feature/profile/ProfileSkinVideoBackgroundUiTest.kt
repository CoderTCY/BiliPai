package com.android.purebilibili.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSkinVideoBackgroundUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun videoFillsBothEdgesAndStaysInsideTheScrollingHeaderLayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("profile-video-", ".mp4", instrumentation.targetContext.cacheDir)
        val scroll = mutableFloatStateOf(0f)
        val pull = mutableFloatStateOf(0f)
        lateinit var layer: GraphicsLayer
        try {
            // Same non-macroblock-aligned dimensions as the online skin in issue #1000.
            instrumentation.context.assets.open("profile_video_1242x1074.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            rule.setContent {
                layer = rememberGraphicsLayer()
                val motion = remember {
                    ProfileBackgroundMotion(
                        scrollOffsetPx = { scroll.floatValue },
                        pullOffsetPx = { pull.floatValue },
                        avatarShiftYPx = { 0f },
                    )
                }
                Box(
                    Modifier.fillMaxWidth().height(400.dp).background(Color.Blue).testTag("viewport")
                        .drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        }
                ) {
                    CompositionLocalProvider(LocalProfileBackgroundMotion provides motion) {
                        Box(Modifier.fillMaxWidth().profileHeaderBackgroundMotion(240.dp)) {
                            ProfileSkinVideoBackground(
                                videoPath = file.absolutePath,
                                playMode = "loop",
                                playbackEnabled = true,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            // The video must be drawn into the Compose layer, including its right edge.
            // A separate SurfaceView leaves a hole in backdrop/captured content.
            rule.waitUntil(15_000) {
                val pixels = runBlocking { layer.toImageBitmap() }.toPixelMap()
                pixels[pixels.width / 2, pixels.height / 4].red > 0.8f
            }
            for ((scrollPx, pullPx) in listOf(0f to 0f, 100f to 0f, 0f to 80f)) {
                rule.runOnIdle {
                    scroll.floatValue = scrollPx
                    pull.floatValue = pullPx
                }
                val image = rule.onNodeWithTag("viewport").captureToImage()
                val pixels = image.toPixelMap()
                val density = image.height / 400f
                val bottom = (240f * density + pullPx - scrollPx).toInt()
                for (x in listOf(2, image.width / 2, image.width - 3)) {
                    for (y in listOf(3, bottom / 2, bottom - 3)) {
                        assertEquals("video at ($x,$y), scroll=$scrollPx pull=$pullPx", 1f, pixels[x, y].red, 0.05f)
                    }
                    assertEquals("clipped below header", Color.Blue, pixels[x, bottom + 3])
                }
            }
        } finally {
            file.delete()
        }
    }
}
