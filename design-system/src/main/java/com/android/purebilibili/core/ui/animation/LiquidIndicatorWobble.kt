package com.android.purebilibili.core.ui.animation

import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

val LocalLiquidIndicatorWobbleEnabled = compositionLocalOf { true }

/** Landing recoil overlaps the final travel and shrink; manual movement keeps its own deformation. */
internal class LiquidIndicatorWobble(initialPosition: Float) {
    private val spring = FloatSpringSpec(dampingRatio = 0.65f, stiffness = 300f, visibilityThreshold = 0.001f)
    private var position = initialPosition
    private var peakSpeed = 0f
    private var distance = 0f
    private var armed = false
    private var rate = 0f
    private var released = false
    private var landingConsumed = false
    // The original velocity deformation remains in charge until it settles naturally.
    val velocityWeight: Float get() = 1f
    var stretch = 0f
        private set
    var isSelectionMotion = false
        private set

    /** A new target cancels the previous tail and arms exactly one landing response. */
    fun onSelectionChanged(preserveRelease: Boolean = false) {
        // A delayed drag-selection acknowledgement must not replay the release recoil.
        if (preserveRelease && released) return
        released = false
        landingConsumed = false
        armed = true
        peakSpeed = max(peakSpeed, 1.5f)
        stretch = 0f
        rate = 0f
        isSelectionMotion = false
    }

    fun onInteractionStarted() {
        reset(position)
    }

    /** Remember the release; settling the position and bloom remains the caller's responsibility. */
    fun onInteractionReleased(nextPosition: Float = position) {
        val pendingDistance = if (nextPosition.isFinite()) abs(nextPosition - position) else 0f
        if (!armed && distance + pendingDistance < 0.02f) return
        released = true
        landingConsumed = false
        armed = true
        peakSpeed = max(peakSpeed, pendingDistance * 60f)
        stretch = 0f
        rate = 0f
        isSelectionMotion = false
    }

    fun advance(
        nextPosition: Float,
        elapsedNanos: Long,
        canSettle: Boolean = true,
        manualInteraction: Boolean = false,
        holdSelectionMotion: Boolean = false,
        landingMotion: Boolean = false,
    ): Boolean {
        if (!nextPosition.isFinite()) {
            reset(position)
            return false
        }
        if (elapsedNanos <= 0L) return false
        if (elapsedNanos > 500_000_000L) {
            reset(nextPosition)
            return false
        }
        val springNanos = elapsedNanos.coerceAtMost(50_000_000L)
        val delta = abs(nextPosition - position)
        position = nextPosition
        val moving = delta > 0.0001f
        if (!landingMotion) landingConsumed = false
        if (moving && !landingConsumed) {
            peakSpeed = max(peakSpeed, (delta * 1_000_000_000f / elapsedNanos).coerceAtMost(30f))
            distance += delta
            armed = armed || distance >= 0.02f
        }
        if (manualInteraction || !canSettle || (!landingMotion && (moving || holdSelectionMotion))) {
            // New input cancels a playing tail. Pending travel still gets one response
            // after its final landing; a stationary press alone must not replay it.
            if (!moving && isSelectionMotion) {
                armed = false
                distance = 0f
                peakSpeed = 0f
            }
            stretch = 0f
            rate = 0f
            isSelectionMotion = false
            // No idle frame loop while waiting: input changes wake the worker again.
            return moving
        }
        if (armed) {
            armed = false
            distance = 0f
            rate = recoilVelocity(peakSpeed)
            landingConsumed = true
            peakSpeed = 0f
            isSelectionMotion = true
        }
        val initial = stretch
        stretch = spring.getValueFromNanos(springNanos, initial, 0f, rate)
        rate = spring.getVelocityFromNanos(springNanos, initial, 0f, rate)
        val active = abs(stretch) > 0.001f || abs(rate) > 0.01f
        if (!active) {
            stretch = 0f
            rate = 0f
            isSelectionMotion = false
            released = false
        }
        return active
    }

    private fun recoilVelocity(speed: Float): Float =
        -(1.8f + 0.5f * (1f - 2f / (exp(2f * speed / 3f) + 1f)))

    fun reset(nextPosition: Float) {
        position = nextPosition
        peakSpeed = 0f
        distance = 0f
        armed = false
        rate = 0f
        released = false
        landingConsumed = false
        isSelectionMotion = false
        stretch = 0f
    }
}

class LiquidIndicatorWobbleState internal constructor(val enabled: Boolean) : State<Float> {
    private val stretch = mutableFloatStateOf(0f)
    override val value: Float get() = stretch.floatValue
    var isSelectionMotion by mutableStateOf(false)
        internal set
    var velocityWeight by mutableFloatStateOf(1f)
        internal set
    internal var floatValue: Float
        get() = stretch.floatValue
        set(value) { stretch.floatValue = value }
}

private data class LiquidWobbleInput(
    val position: Float,
    val canSettle: Boolean,
    val selectionKey: Any?,
    val manual: Boolean,
    val selectionMotion: Boolean?,
)

/** One frame worker per indicator; new targets cancel the previous landing response. */
@Composable
fun rememberLiquidIndicatorWobble(
    enabled: Boolean,
    geometryKey: Any,
    canSettle: () -> Boolean = { true },
    selectionKey: () -> Any? = { null },
    interactionActive: () -> Boolean = { false },
    selectionMotionActive: () -> Boolean? = { null },
    position: () -> Float,
): LiquidIndicatorWobbleState {
    val latestPosition = rememberUpdatedState(position)
    val latestCanSettle = rememberUpdatedState(canSettle)
    val latestSelectionKey = rememberUpdatedState(selectionKey)
    val latestInteractionActive = rememberUpdatedState(interactionActive)
    val latestSelectionMotion = rememberUpdatedState(selectionMotionActive)
    val motionEnabled = enabled && LocalLiquidIndicatorWobbleEnabled.current &&
        com.android.purebilibili.core.ui.LocalComponentMotionEnabled.current &&
        !com.android.purebilibili.core.ui.LocalSystemReduceMotion.current
    val result = remember(motionEnabled, geometryKey) { LiquidIndicatorWobbleState(motionEnabled) }
    LaunchedEffect(result) {
        if (!motionEnabled) return@LaunchedEffect
        val wobble = LiquidIndicatorWobble(latestPosition.value())
        fun input() = LiquidWobbleInput(
            latestPosition.value(), latestCanSettle.value(), latestSelectionKey.value(),
            latestInteractionActive.value(), latestSelectionMotion.value(),
        )
        coroutineScope {
            var worker: Job? = null
            // A layout may mount this state during an already active selection.
            // Preserve the explicit start without animating an ordinary idle mount.
            var observed = input().copy(selectionMotion = false, manual = false)
            snapshotFlow { input() }.collect { next ->
                if (next.position.isFinite() && next != observed) {
                    val selectionChanged = next.selectionKey != observed.selectionKey
                    val pressed = !observed.manual && next.manual
                    val released = observed.manual && !next.manual
                    val explicitTapStarted = next.selectionMotion == true && observed.selectionMotion != true
                    if (pressed) {
                        wobble.onInteractionStarted()
                    } else if (released) {
                        wobble.onInteractionReleased(next.position)
                    } else if (!next.manual && (selectionChanged || explicitTapStarted)) {
                        wobble.onSelectionChanged(preserveRelease = !explicitTapStarted)
                    }
                    result.isSelectionMotion = wobble.isSelectionMotion
                    result.velocityWeight = wobble.velocityWeight
                    // Clear old tails immediately on a new target, press or blocked phase.
                    result.floatValue = if (next.manual || !next.canSettle) 0f
                        else wobble.stretch.coerceIn(-0.18f, 0.18f)
                    observed = next
                    if (worker?.isActive != true) worker = launch {
                        // Process the first delivered frame too; waiting for a second
                        // frame added 16–33ms and could miss a short tap's travel entirely.
                        var previous: Long? = null
                        do {
                            val now = withFrameNanos { it }
                            val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                            val current = input()
                            val active = if (durationScale > 0f) {
                                wobble.advance(
                                    current.position, ((previous?.let { now - it } ?: 16_666_667L) / durationScale).toLong(),
                                    current.canSettle, current.manual, current.selectionMotion == true,
                                    landingMotion = current.canSettle,
                                )
                            } else {
                                wobble.reset(current.position)
                                false
                            }
                            result.isSelectionMotion = wobble.isSelectionMotion
                            result.velocityWeight = wobble.velocityWeight
                            result.floatValue = if (current.manual) 0f else wobble.stretch.coerceIn(-0.18f, 0.18f)
                            previous = now
                        } while (active)
                    }
                }
            }
        }
    }
    return result
}

/** Recoil joins braking within the last 2.5% of the available travel range. */
fun isLiquidIndicatorNearLanding(position: Float, target: Float, range: Float): Boolean =
    position.isFinite() && target.isFinite() && range.isFinite() &&
        abs(position - target) <= (abs(range) * LiquidIndicatorMotion.ReleaseDistanceFraction).coerceAtLeast(0.001f)

/** Shared landing policy for taps, indicator drags and page swipes. Held input always wins. */
fun canStartLiquidIndicatorRecoil(
    position: Float,
    target: Float,
    range: Float,
    interactionActive: Boolean,
    returning: Boolean = true,
): Boolean = !interactionActive && returning && isLiquidIndicatorNearLanding(position, target, range)

fun liquidWobbleScaleX(base: Float, stretch: Float): Float = base * (1f + stretch)
fun liquidWobbleScaleY(base: Float, stretch: Float): Float = base * (1f - stretch * 1.4f)

/** Material stays active through the visible shape's return, including spring undershoot. */
fun liquidIndicatorShapeProgress(scaleX: Float, scaleY: Float, bloomScale: Float): Float =
    (max(abs(scaleX - 1f), abs(scaleY - 1f)) / (bloomScale - 1f).coerceAtLeast(0.18f))
        .coerceIn(0f, 1f)

/** Rendering checks interaction state directly so a previous recoil never leaks into a new drag. */
fun resolveLiquidIndicatorRecoilStretch(stretch: Float, canSettle: Boolean): Float =
    if (canSettle) stretch else 0f

/** Logical-axis shift: compress against an endpoint, then peel away as the shape rebounds. */
fun liquidWobbleEdgeTranslationPx(
    stretch: Float,
    position: Float,
    lastIndex: Int,
    extentPx: Float,
    edgeInsetPx: Float,
    baseScale: Float = 1f,
): Float {
    if (lastIndex <= 0 || !position.isFinite() || !stretch.isFinite() || extentPx <= 0f || stretch == 0f) return 0f
    val edgeDistance: Float
    val edge = when {
        position < 0.35f -> { edgeDistance = abs(position); -1f }
        position > lastIndex - 0.35f -> { edgeDistance = abs(position - lastIndex); 1f }
        else -> return 0f
    }
    val proximity = (1f - edgeDistance / 0.35f).coerceIn(0f, 1f)
    val edgeWeight = proximity * proximity * (3f - 2f * proximity)
    val inset = edgeInsetPx.coerceAtLeast(0f)
    // Close the measured gap early enough for both tap braking and release recoil.
    // Compensate the width lost to squash and any remaining press magnification.
    val contact = if (stretch < 0f) (-stretch / 0.02f).coerceAtMost(1f)
        else (stretch / 0.04f).coerceAtMost(1f)
    // Bloom compensation is much larger than the inset. Ramping it with the
    // 0.02 squash threshold made every zero crossing shove a magnified endpoint
    // sideways. Ease that correction over the shape's full collision range;
    // keep squash/rebound amplitude and full-contact geometry unchanged.
    val magnificationProgress = (abs(stretch) / 0.1f).coerceIn(0f, 1f)
    val magnificationContact = magnificationProgress * magnificationProgress *
        (3f - 2f * magnificationProgress)
    val magnificationCorrection = extentPx * (1f - baseScale) * (1f + stretch) / 2f * magnificationContact
    val shift = -stretch * extentPx / 2f + magnificationCorrection +
        (if (stretch < 0f) contact else -contact) * inset
    return edge * shift * edgeWeight
}
