package com.android.purebilibili.navigation

import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.core.util.BilibiliNavigationTargetParser
import java.net.URI

internal sealed interface SearchSubmitAction {
    data object Ignore : SearchSubmitAction
    data class OpenSearch(val keyword: String) : SearchSubmitAction
    data class OpenNativeTarget(val target: BilibiliNavigationTarget) : SearchSubmitAction
}

internal fun resolveSearchSubmitAction(rawKeyword: String): SearchSubmitAction {
    val keyword = rawKeyword.trim()
    if (keyword.isEmpty()) return SearchSubmitAction.Ignore

    return when (val target = BilibiliNavigationTargetParser.parse(keyword)) {
        null -> SearchSubmitAction.OpenSearch(keyword)
        is BilibiliNavigationTarget.Search -> SearchSubmitAction.OpenSearch(target.keyword)
        else -> SearchSubmitAction.OpenNativeTarget(target)
    }
}

/**
 * A short Bilibili link needs a redirect before it can be routed. If resolution fails,
 * keep the original text searchable instead of opening an unrelated page.
 */
internal suspend fun resolveSearchSubmitActionWithRedirect(
    rawKeyword: String,
    resolveShortLink: suspend (String) -> BilibiliNavigationTarget? = BilibiliNavigationTargetParser::resolve
): SearchSubmitAction {
    val fallback = resolveSearchSubmitAction(rawKeyword)
    if (fallback !is SearchSubmitAction.OpenSearch) return fallback

    val url = (resolveBilibiliLinkNavigationAction(rawKeyword) as? BilibiliLinkNavigationAction.InAppWeb)?.url
        ?: return fallback
    if (!runCatching { URI(url).host.equals("b23.tv", ignoreCase = true) }.getOrDefault(false)) {
        return fallback
    }

    return when (val target = resolveShortLink(url)) {
        null -> fallback
        is BilibiliNavigationTarget.Search -> SearchSubmitAction.OpenSearch(target.keyword)
        else -> SearchSubmitAction.OpenNativeTarget(target)
    }
}
