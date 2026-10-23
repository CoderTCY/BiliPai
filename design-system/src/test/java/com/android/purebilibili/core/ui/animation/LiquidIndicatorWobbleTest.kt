package com.android.purebilibili.core.ui.animation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquidIndicatorWobbleTest {
    @Test
    fun `tap indicator drag and page swipe share the released landing trajectory`() {
        val trajectories = listOf("tap", "indicator drag", "page swipe").map { source ->
            val wobble = LiquidIndicatorWobble(0f)
            val held = source != "tap"
            if (held) wobble.onInteractionStarted() else wobble.onSelectionChanged()
            wobble.advance(0.6f, frame, canSettle = false, manualInteraction = held)
            assertEquals(0f, wobble.stretch)
            if (held) wobble.onInteractionReleased(0.6f)
            val shape = mutableListOf<Float>()
            repeat(120) { index ->
                val position = if (index == 0) 0.96f else 1f
                val ready = canStartLiquidIndicatorRecoil(
                    position = position, target = 1f, range = 4f,
                    interactionActive = false, returning = true,
                )
                wobble.advance(position, frame, canSettle = ready, landingMotion = ready)
                shape += wobble.stretch
            }
            assertTrue(shape.first() < 0f)
            assertEquals(0f, shape.last())
            repeat(30) { assertFalse(wobble.advance(1f, frame, landingMotion = true)) }
            shape
        }
        assertEquals(trajectories[0], trajectories[1])
        assertEquals(trajectories[0], trajectories[2])
    }

    @Test
    fun `held input and intermediate tabs cannot trigger a final target recoil`() {
        assertFalse(canStartLiquidIndicatorRecoil(2.96f, 3f, 4f, interactionActive = true))
        assertFalse(canStartLiquidIndicatorRecoil(1f, 3f, 4f, interactionActive = false))
        assertFalse(canStartLiquidIndicatorRecoil(2f, 3f, 4f, interactionActive = false))
        assertFalse(canStartLiquidIndicatorRecoil(2.96f, 3f, 4f, interactionActive = false, returning = false))
        // Released pager settling may enter recoil before reaching the exact integer position.
        assertTrue(canStartLiquidIndicatorRecoil(2.96f, 3f, 4f, interactionActive = false))
    }

    @Test
    fun `landing gate allows residual travel while the enlarged capsule shrinks`() {
        val wobble = LiquidIndicatorWobble(0f)
        wobble.onSelectionChanged()
        repeat(12) {
            wobble.advance((it + 1) * 0.07f, frame, canSettle = false)
            assertEquals(0f, wobble.stretch)
        }
        // The caller opens the landing gate while position and scale are still changing.
        wobble.advance(0.96f, frame, holdSelectionMotion = true, landingMotion = true)
        assertTrue(wobble.stretch < 0f)
        val first = wobble.stretch
        wobble.advance(0.98f, frame, holdSelectionMotion = true, landingMotion = true)
        assertTrue(wobble.stretch < first)
        assertTrue(wobble.isSelectionMotion)
        // Near-target drift must not inject a fresh impulse after the tail has finished.
        repeat(120) {
            wobble.advance(0.98f + (it + 1) * 0.00016f, frame, landingMotion = true)
        }
        assertEquals(0f, wobble.stretch)
        repeat(30) { assertFalse(wobble.advance(1f, frame, landingMotion = true)) }
    }

    @Test
    fun `new travel closes the landing gate and cancels the old tail`() {
        val wobble = LiquidIndicatorWobble(1f)
        wobble.onSelectionChanged()
        wobble.advance(1f, frame, landingMotion = true)
        assertTrue(wobble.stretch < 0f)
        wobble.onSelectionChanged()
        wobble.advance(0.7f, frame, canSettle = false)
        assertEquals(0f, wobble.stretch)
        wobble.advance(0.02f, frame, landingMotion = true)
        assertTrue(wobble.stretch < 0f)
        wobble.onInteractionStarted()
        wobble.advance(0.1f, frame, manualInteraction = true, landingMotion = true)
        assertEquals(0f, wobble.stretch)
    }

    @Test
    fun `landing distance follows the available range and rejects invalid positions`() {
        assertFalse(isLiquidIndicatorNearLanding(0.5f, 1f, 4f))
        assertTrue(isLiquidIndicatorNearLanding(0.95f, 1f, 4f))
        assertTrue(isLiquidIndicatorNearLanding(0.02f, 0f, 1f))
        assertFalse(isLiquidIndicatorNearLanding(0.04f, 0f, 1f))
        assertTrue(isLiquidIndicatorNearLanding(0f, 0f, 0f))
        assertFalse(isLiquidIndicatorNearLanding(Float.NaN, 0f, 4f))
        assertFalse(isLiquidIndicatorNearLanding(0f, Float.POSITIVE_INFINITY, 4f))
    }

    @Test
    fun `travel stays undeformed until the caller opens the landing gate`() {
        val wobble = LiquidIndicatorWobble(0f)
        wobble.onSelectionChanged()
        repeat(12) {
            wobble.advance((it + 1) / 12f, frame, canSettle = false, holdSelectionMotion = true)
            assertEquals(0f, wobble.stretch)
            assertFalse(wobble.isSelectionMotion)
        }
        repeat(30) {
            assertFalse(wobble.advance(1f, frame, canSettle = false))
            assertEquals(0f, wobble.stretch)
        }
        // A held selection still blocks recoil even if the scale gate is ready.
        wobble.advance(1f, frame, holdSelectionMotion = true)
        assertEquals(0f, wobble.stretch)
        assertTrue(wobble.advance(1f, frame))
        assertTrue(wobble.stretch < 0f)
        repeat(180) { wobble.advance(1f, frame) }
        repeat(30) { assertFalse(wobble.advance(1f, frame)) }
    }

    @Test
    fun `release stays armed while outside the landing gate`() {
        val wobble = LiquidIndicatorWobble(0f)
        wobble.onInteractionStarted()
        wobble.advance(0.6f, frame, manualInteraction = true)
        wobble.onInteractionReleased()
        for (position in listOf(0.7f, 0.85f, 0.95f, 1f)) {
            wobble.advance(position, frame, canSettle = false)
            assertEquals(0f, wobble.stretch)
        }
        repeat(30) {
            wobble.advance(1f, frame, canSettle = false)
            assertEquals(0f, wobble.stretch)
            assertEquals(1f, wobble.velocityWeight)
        }
        wobble.advance(1f, frame)
        assertTrue(wobble.stretch < 0f)
    }

    @Test
    fun `short drag and tap landing between samples each receive one deferred response`() {
        for (drag in listOf(false, true)) {
            val wobble = LiquidIndicatorWobble(1f)
            if (drag) {
                wobble.onInteractionStarted()
                wobble.onInteractionReleased(nextPosition = 1.2f)
            } else {
                wobble.onSelectionChanged()
            }
            repeat(10) {
                wobble.advance(1.2f, frame, canSettle = false)
                assertEquals(0f, wobble.stretch)
            }
            wobble.advance(1.2f, frame)
            assertTrue(wobble.stretch < 0f)
            repeat(180) { wobble.advance(1.2f, frame) }
            repeat(30) { assertFalse(wobble.advance(1.2f, frame)) }
        }
    }

    @Test
    fun `new press cancels a tail without replaying it on stationary release`() {
        val wobble = LiquidIndicatorWobble(1f)
        wobble.onSelectionChanged()
        wobble.advance(1f, frame)
        assertTrue(wobble.stretch != 0f)
        wobble.onInteractionStarted()
        assertEquals(0f, wobble.stretch)
        assertFalse(wobble.isSelectionMotion)
        wobble.onInteractionReleased()
        repeat(120) { assertFalse(wobble.advance(1f, frame)) }
    }

    @Test
    fun `tap reversal clears a playing tail and waits for the new landing`() {
        val wobble = LiquidIndicatorWobble(1f)
        wobble.onSelectionChanged()
        wobble.advance(1f, frame)
        assertTrue(wobble.stretch != 0f)
        wobble.onSelectionChanged()
        assertEquals(0f, wobble.stretch)
        repeat(10) {
            wobble.advance(1f - (it + 1) / 10f, frame, holdSelectionMotion = true)
            assertEquals(0f, wobble.stretch)
        }
        wobble.advance(0f, frame)
        assertTrue(wobble.stretch < 0f)
    }

    @Test
    fun `completed drag release does not suppress a later programmatic selection`() {
        val wobble = LiquidIndicatorWobble(0f)
        wobble.onInteractionStarted()
        wobble.advance(1f, frame, manualInteraction = true)
        wobble.onInteractionReleased()
        repeat(180) { wobble.advance(1f, frame) }
        wobble.onSelectionChanged(preserveRelease = true)
        wobble.advance(1f, frame)
        assertTrue(wobble.stretch < 0f)
    }

    @Test
    fun `selection acknowledgement preserves the entire drag release trajectory`() {
        val reference = LiquidIndicatorWobble(0f)
        val acknowledged = LiquidIndicatorWobble(0f)
        for (wobble in listOf(reference, acknowledged)) {
            wobble.onInteractionStarted()
            wobble.advance(0.6f, frame, manualInteraction = true)
            wobble.onInteractionReleased()
        }
        repeat(120) { index ->
            if (index == 2 || index == 8) {
                acknowledged.onSelectionChanged(preserveRelease = true)
            }
            val position = (0.6f + index * 0.04f).coerceAtMost(1f)
            reference.advance(position, frame)
            acknowledged.advance(position, frame)
            assertEquals(reference.stretch, acknowledged.stretch)
            assertEquals(reference.velocityWeight, acknowledged.velocityWeight)
        }
        assertEquals(0f, acknowledged.stretch)
    }

    @Test
    fun `material follows final bloom and recoil including zero crossings and undershoot`() {
        val bloom = 1.39f
        for (stretch in listOf(-0.12f, -0.01f, 0f, 0.01f, 0.08f)) {
            val progress = liquidIndicatorShapeProgress(
                liquidWobbleScaleX(bloom, stretch), liquidWobbleScaleY(bloom, stretch), bloom,
            )
            assertTrue(progress > 0f)
        }
        assertEquals(0f, liquidIndicatorShapeProgress(1f, 1f, bloom))
        assertTrue(liquidIndicatorShapeProgress(0.99f, 1.014f, bloom) > 0f)
        assertTrue(liquidIndicatorShapeProgress(1.1f, 0.9f, 1f).isFinite())
    }

    @Test
    fun `stationary pointer release has no unsolicited recoil`() {
        val wobble = LiquidIndicatorWobble(1f)
        wobble.onInteractionStarted()
        wobble.advance(1f, frame, canSettle = false, manualInteraction = true)
        wobble.onInteractionReleased()
        repeat(60) {
            assertFalse(wobble.advance(1f, frame))
            assertEquals(0f, wobble.stretch)
        }
    }

    @Test
    fun `release recoil converges consistently at 60 90 and 120 hz`() {
        val samples = listOf(60, 90, 120).map { fps ->
            val wobble = LiquidIndicatorWobble(0f)
            val step = 1_000_000_000L / fps
            wobble.onInteractionStarted()
            repeat(fps / 5) {
                wobble.advance((it + 1) * 5f / fps, step,
                    canSettle = false, manualInteraction = true)
            }
            wobble.onInteractionReleased()
            var minimum = 0f
            repeat(fps * 2) {
                wobble.advance(1f, step)
                minimum = minOf(minimum, wobble.stretch)
            }
            assertEquals(0f, wobble.stretch)
            assertFalse(wobble.isSelectionMotion)
            minimum
        }
        assertTrue(samples.max() - samples.min() < 0.005f)
    }

    @Test
    fun `press bloom keeps the original multiplicative squash and stretch`() {
        val base = 78f / 56f
        for (stretch in listOf(-0.18f, -0.1f, 0f, 0.1f, 0.18f)) {
            assertEquals(base * (1f + stretch), liquidWobbleScaleX(base, stretch))
            assertEquals(base * (1f - stretch * 1.4f), liquidWobbleScaleY(base, stretch))
        }
    }

    @Test
    fun `endpoint correction remains continuous across the old contact threshold`() {
        for (base in listOf(1f, 78f / 56f)) {
            for (stretch in listOf(-0.12f, 0.04f)) {
                for (position in listOf(0.02f, 0.35f, 2.65f, 2.98f)) {
                    val before = liquidWobbleEdgeTranslationPx(stretch, position - 0.0001f, 3, 100f, 4f, base)
                    val after = liquidWobbleEdgeTranslationPx(stretch, position + 0.0001f, 3, 100f, 4f, base)
                    assertTrue(kotlin.math.abs(after - before) < 0.1f)
                }
            }
        }
    }

    @Test
    fun `four dp edge contact compensates remaining press magnification on both endpoints`() {
        val extent = 100f
        val gap = 4f
        for (scale in listOf(1f, 1.05f, 1.35f)) {
            for (stretch in listOf(-0.1f, -0.12f, -0.16f)) {
                val shift = liquidWobbleEdgeTranslationPx(stretch, 0f, 3, extent, gap, scale)
                val actualExtent = extent * scale * (1f + stretch)
                val edge = gap + shift + (extent - actualExtent) / 2f
                assertEquals(0f, edge, 0.0001f)
                assertEquals(-shift,
                    liquidWobbleEdgeTranslationPx(stretch, 3f, 3, extent, gap, scale), 0.0001f)
            }
        }
        // Ordinary press/drag remains untouched when no new shape is being drawn.
        assertEquals(0f, liquidWobbleEdgeTranslationPx(0f, 0f, 3, extent, gap, 1.35f))
    }

    @Test
    fun `magnified first and last tabs do not snap sideways as squash crosses zero`() {
        for (position in listOf(0f, 3f)) {
            for (base in listOf(1f, 78f / 56f)) {
                val before = liquidWobbleEdgeTranslationPx(-0.001f, position, 3, 100f, 4f, base)
                val after = liquidWobbleEdgeTranslationPx(0.001f, position, 3, 100f, 4f, base)
                val unscaledBefore = liquidWobbleEdgeTranslationPx(-0.001f, position, 3, 100f, 4f, 1f)
                val unscaledAfter = liquidWobbleEdgeTranslationPx(0.001f, position, 3, 100f, 4f, 1f)
                assertTrue(kotlin.math.abs(before - unscaledBefore) < 0.01f)
                assertTrue(kotlin.math.abs(after - unscaledAfter) < 0.01f)
            }
        }
    }

    @Test
    fun `endpoint squash reaches the shell and rebounds away without crossing it`() {
        val width = 100f
        val inset = 3f
        for (stretch in listOf(-0.12f, -0.16f)) {
            val leftShift = liquidWobbleEdgeTranslationPx(stretch, 0f, 3, width, inset)
            val rightShift = liquidWobbleEdgeTranslationPx(stretch, 3f, 3, width, inset)
            assertEquals(0f, inset + leftShift - width * stretch / 2f, 0.0001f)
            assertEquals(-leftShift, rightShift, 0.0001f)
        }
        val rebound = 0.04f
        val reboundShift = liquidWobbleEdgeTranslationPx(rebound, 0f, 3, width, inset)
        assertTrue(inset + reboundShift - width * rebound / 2f > inset)
        assertEquals(0f, liquidWobbleEdgeTranslationPx(-0.15f, 1f, 3, width, inset))
        assertEquals(0f, liquidWobbleEdgeTranslationPx(0f, 0f, 3, width, inset))
    }

    @Test
    fun `slow drag still has a visible landing squash without deforming during movement`() {
        val wobble = LiquidIndicatorWobble(0f)
        repeat(20) {
            wobble.advance((it + 1) * 0.005f, 16_666_667L, canSettle = false)
            assertEquals(0f, wobble.stretch)
        }
        var minimum = 0f
        repeat(90) {
            wobble.advance(0.1f, 16_666_667L)
            minimum = minOf(minimum, wobble.stretch)
        }
        assertTrue(minimum < -0.03f)
    }

    private val frame = 16_666_667L

    @Test
    fun `selection change recoils without requiring sampled drag distance`() {
        // A click can land between two samples, leaving the observer at its final position.
        val wobble = LiquidIndicatorWobble(1f)
        wobble.onSelectionChanged()
        repeat(30) {
            assertFalse(wobble.advance(1f, frame, canSettle = false))
            assertEquals(0f, wobble.stretch)
        }
        var recoiled = false
        repeat(180) {
            wobble.advance(1f, frame)
            recoiled = recoiled || wobble.stretch < -0.001f
        }
        assertTrue(recoiled)
        assertEquals(0f, wobble.stretch)
        repeat(60) { assertFalse(wobble.advance(1f, frame)) }
    }

    @Test
    fun `rapid click selections supersede a playing recoil and wait for the latest landing`() {
        val wobble = LiquidIndicatorWobble(0f)
        wobble.onSelectionChanged()
        repeat(10) { wobble.advance(0f, frame) }
        assertTrue(wobble.stretch != 0f)
        wobble.onSelectionChanged()
        assertEquals(0f, wobble.stretch)
        wobble.advance(0.001f, frame, canSettle = false)
        repeat(10) { wobble.advance(0.001f, frame, canSettle = false) }
        assertEquals(0f, wobble.stretch)
        var recoiled = false
        repeat(180) {
            wobble.advance(0.001f, frame)
            recoiled = recoiled || wobble.stretch < -0.001f
        }
        assertTrue(recoiled)
        assertEquals(0f, wobble.stretch)
    }

    @Test
    fun `rendering a new drag suppresses the previous tail before its worker resumes`() {
        for (tail in listOf(-0.1f, 0.05f)) {
            assertEquals(0f, resolveLiquidIndicatorRecoilStretch(tail, canSettle = false))
            assertEquals(tail, resolveLiquidIndicatorRecoilStretch(tail, canSettle = true))
        }
    }

    @Test
    fun `moving adds no shape and stopping plays one recoil then returns exactly to rest`() {
        val wobble = LiquidIndicatorWobble(0f)
        repeat(18) {
            assertTrue(wobble.advance((it + 1) / 18f, frame))
            assertEquals(0f, wobble.stretch)
            assertEquals(1.2f, liquidWobbleScaleX(1.2f, wobble.stretch))
            assertEquals(0.9f, liquidWobbleScaleY(0.9f, wobble.stretch))
        }
        var compressed = false
        var rebounded = false
        repeat(180) {
            wobble.advance(1f, frame)
            compressed = compressed || wobble.stretch < -0.001f
            rebounded = rebounded || wobble.stretch > 0.001f
        }
        assertTrue(compressed)
        assertTrue(rebounded)
        assertEquals(0f, wobble.stretch)
        repeat(60) { assertFalse(wobble.advance(1f, frame)) }
    }

    @Test
    fun `closed landing gate defers the recoil`() {
        val wobble = LiquidIndicatorWobble(0f)
        repeat(18) { wobble.advance((it + 1) / 18f, frame, canSettle = false) }
        repeat(60) {
            assertFalse(wobble.advance(1f, frame, canSettle = false))
            assertEquals(0f, wobble.stretch)
        }
        var recoiled = false
        repeat(30) {
            wobble.advance(1f, frame, canSettle = true)
            recoiled = recoiled || wobble.stretch < -0.001f
        }
        assertTrue(recoiled)
    }

    @Test
    fun `new movement or stationary press cancels a playing recoil`() {
        val wobble = LiquidIndicatorWobble(0f)
        repeat(18) { wobble.advance((it + 1) / 18f, frame) }
        repeat(10) { wobble.advance(1f, frame) }
        assertTrue(wobble.stretch != 0f)
        wobble.advance(1.1f, frame)
        assertEquals(0f, wobble.stretch)
        repeat(10) { wobble.advance(1.1f, frame) }
        assertTrue(wobble.stretch != 0f)
        assertFalse(wobble.advance(1.1f, frame, canSettle = false))
        assertEquals(0f, wobble.stretch)
        repeat(60) { assertFalse(wobble.advance(1.1f, frame)) }
    }

    @Test
    fun `both directions recoil equally and idle taps do not arm recoil`() {
        val forward = LiquidIndicatorWobble(0f)
        val backward = LiquidIndicatorWobble(4f)
        repeat(18) {
            val position = (it + 1) / 18f
            forward.advance(position, frame)
            backward.advance(4f - position, frame)
        }
        repeat(120) {
            forward.advance(1f, frame)
            backward.advance(3f, frame)
            assertEquals(forward.stretch, backward.stretch, 0.0001f)
        }
        val idle = LiquidIndicatorWobble(2f)
        repeat(10) { assertFalse(idle.advance(2f, frame, canSettle = false)) }
        repeat(60) { assertFalse(idle.advance(2f, frame)) }
    }

    @Test
    fun `suspension or invalid samples clear the pending recoil`() {
        val wobble = LiquidIndicatorWobble(0f)
        repeat(10) { wobble.advance((it + 1) / 10f, frame) }
        assertFalse(wobble.advance(1f, 1_000_000_000L))
        repeat(60) { assertFalse(wobble.advance(1f, frame)) }
        wobble.advance(2f, frame)
        assertFalse(wobble.advance(Float.NaN, frame))
        assertEquals(0f, wobble.stretch)
        repeat(60) { assertFalse(wobble.advance(2f, frame)) }
    }
}
