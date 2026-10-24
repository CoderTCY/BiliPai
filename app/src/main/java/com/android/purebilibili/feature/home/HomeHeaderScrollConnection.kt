package com.android.purebilibili.feature.home

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.android.purebilibili.core.store.CommonListHeaderCollapseMode
import com.android.purebilibili.core.store.HomeBarHideType
import com.android.purebilibili.feature.home.policy.BottomBarVisibilityIntent
import com.android.purebilibili.feature.home.policy.canRevealHomeHeaderForList
import com.android.purebilibili.feature.home.policy.reduceHomePreScroll
import com.android.purebilibili.feature.home.policy.resolveHomeHeaderRevealConsumedY
import com.android.purebilibili.feature.home.policy.resolveHomeHeaderListIndex
import com.android.purebilibili.feature.home.policy.shouldHandleHomeVerticalPreScroll

@Immutable
internal data class HomeHeaderScrollConfiguration(
    val collapseEnabled: Boolean,
    val collapseDistancePx: Float,
    val collapseTabs: Boolean,
    val bottomBarAutoHideEnabled: Boolean,
    val useSideNavigation: Boolean,
    val liquidGlassEnabled: Boolean,
    val hideType: HomeBarHideType,
)

@Composable
internal fun rememberHomeHeaderScrollConnection(
    configuration: HomeHeaderScrollConfiguration,
    pagerState: PagerState,
    topTabEntries: List<HomeTopTabEntry>,
    activeGridState: LazyStaggeredGridState?,
    subscriptionListState: LazyStaggeredGridState,
    revealLocked: Boolean,
    headerOffsetProvider: () -> Float,
    globalScrollOffset: MutableFloatState,
    onHeaderOffsetChanged: (Float, Boolean) -> Unit,
    onTabsCollapsedChanged: (Boolean) -> Unit,
    onBottomBarVisibleChanged: (Boolean) -> Unit,
    refreshInteractionActive: Boolean = false,
): NestedScrollConnection {
    val currentRefreshInteractionActive = rememberUpdatedState(refreshInteractionActive)
    val currentOnHeaderOffsetChanged = rememberUpdatedState(onHeaderOffsetChanged)
    val currentOnTabsCollapsedChanged = rememberUpdatedState(onTabsCollapsedChanged)
    val currentOnBottomBarVisibleChanged = rememberUpdatedState(onBottomBarVisibleChanged)
    return remember(
        configuration,
        pagerState,
        topTabEntries,
        activeGridState,
        subscriptionListState,
        revealLocked,
        headerOffsetProvider,
        globalScrollOffset,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 下拉、请求及回弹共用保护，不让同一刷新会话反向收起顶栏。
                if (currentRefreshInteractionActive.value) {
                    currentOnHeaderOffsetChanged.value(0f, false)
                    currentOnTabsCollapsedChanged.value(false)
                    return Offset.Zero
                }
                if (revealLocked || !shouldHandleHomeVerticalPreScroll(
                        deltaX = available.x,
                        deltaY = available.y,
                    )
                ) {
                    return Offset.Zero
                }
                val onSubscriptionTab = resolveHomeTopTabEntryOrNull(
                    topTabEntries,
                    pagerState.currentPage,
                ) == HomeTopTabEntry.Subscriptions
                val headerListIndex = resolveHomeHeaderListIndex(
                    displayedEntryIsSubscription = onSubscriptionTab,
                    categoryFirstVisibleIndex = activeGridState?.firstVisibleItemIndex ?: 0,
                    subscriptionFirstVisibleIndex = subscriptionListState.firstVisibleItemIndex,
                )
                val activeList = if (onSubscriptionTab) subscriptionListState else activeGridState
                // 展开判定沿用首项可见语义：item 0 露出即可跟随回顶展开，避免手势
                // 恰好在到顶瞬间结束导致顶栏滞留收起；消费判定才要求真正贴顶。
                val canRevealHeader = canRevealHomeHeaderForList(
                    firstVisibleItemIndex = headerListIndex,
                    listMissing = !onSubscriptionTab && activeGridState == null,
                )
                val listAtTop = activeList?.let { !it.canScrollBackward } == true
                val oldHeaderOffset = headerOffsetProvider()
                val revealConsumedY = resolveHomeHeaderRevealConsumedY(
                    currentHeaderOffsetPx = oldHeaderOffset,
                    deltaY = available.y,
                    listAtTop = listAtTop,
                    collapseEnabled = configuration.collapseEnabled,
                    userInput = source == NestedScrollSource.UserInput,
                )
                val scrollUpdate = reduceHomePreScroll(
                    currentHeaderOffsetPx = oldHeaderOffset,
                    deltaY = available.y,
                    minHeaderOffsetPx = -configuration.collapseDistancePx,
                    canRevealHeader = canRevealHeader,
                    collapseMode = CommonListHeaderCollapseMode.SHOW_AT_TOP_ONLY,
                    isHeaderCollapseEnabled = configuration.collapseEnabled,
                    isBottomBarAutoHideEnabled = configuration.bottomBarAutoHideEnabled,
                    useSideNavigation = configuration.useSideNavigation,
                    liquidGlassEnabled = configuration.liquidGlassEnabled,
                    currentGlobalScrollOffset = globalScrollOffset.floatValue,
                    hideType = configuration.hideType,
                    isHeaderRevealLocked = revealLocked,
                    headerConsumedY = revealConsumedY,
                )
                currentOnHeaderOffsetChanged.value(
                    scrollUpdate.headerOffsetPx,
                    scrollUpdate.shouldAnimateHeader,
                )
                currentOnTabsCollapsedChanged.value(
                    configuration.collapseTabs && !revealLocked &&
                        (headerListIndex > 0 || headerOffsetProvider() < -0.5f)
                )
                scrollUpdate.globalScrollOffset?.let { globalScrollOffset.floatValue = it }
                when (scrollUpdate.bottomBarVisibilityIntent) {
                    BottomBarVisibilityIntent.SHOW -> currentOnBottomBarVisibleChanged.value(true)
                    BottomBarVisibilityIntent.HIDE -> currentOnBottomBarVisibleChanged.value(false)
                    null -> Unit
                }
                if (headerListIndex == 0 && headerOffsetProvider() >= -0.5f) {
                    currentOnTabsCollapsedChanged.value(false)
                }
                // 浏览时只观察列表；贴顶下拉先展开顶栏，剩余位移由刷新容器处理。
                return Offset(0f, revealConsumedY)
            }
        }
    }
}
