package com.android.purebilibili.navigation

import com.android.purebilibili.core.util.BilibiliNavigationTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse

class SearchSubmitPolicyTest {

    @Test
    fun `search submit opens native video for raw bv`() {
        val action = resolveSearchSubmitAction("BV1xx411c7mD")

        val target = assertIs<SearchSubmitAction.OpenNativeTarget>(action).target
        assertEquals(BilibiliNavigationTarget.Video("BV1xx411c7mD"), target)
    }

    @Test
    fun `search submit opens native video for bilibili link`() {
        val action = resolveSearchSubmitAction("https://www.bilibili.com/video/BV1xx411c7mD")

        val target = assertIs<SearchSubmitAction.OpenNativeTarget>(action).target
        assertEquals(BilibiliNavigationTarget.Video("BV1xx411c7mD"), target)
    }

    @Test
    fun `search submit keeps normal text as search keyword`() {
        val action = assertIs<SearchSubmitAction.OpenSearch>(resolveSearchSubmitAction("  猫和老鼠  "))

        assertEquals("猫和老鼠", action.keyword)
    }

    @Test
    fun `search submit unwraps search url keyword`() {
        val action = assertIs<SearchSubmitAction.OpenSearch>(
            resolveSearchSubmitAction("https://search.bilibili.com/all?keyword=%E7%8C%AB")
        )

        assertEquals("猫", action.keyword)
    }

    @Test
    fun `search submit follows bilibili short links to native targets`() = runBlocking {
        val action = resolveSearchSubmitActionWithRedirect("b23.tv/abc123") { url ->
            assertEquals("https://b23.tv/abc123", url)
            BilibiliNavigationTarget.Live(123L)
        }

        assertEquals(
            SearchSubmitAction.OpenNativeTarget(BilibiliNavigationTarget.Live(123L)),
            action
        )
    }

    @Test
    fun `failed short link stays searchable and ordinary keywords skip redirect`() = runBlocking {
        val shortLink = "https://b23.tv/unavailable"
        assertEquals(
            SearchSubmitAction.OpenSearch(shortLink),
            resolveSearchSubmitActionWithRedirect(shortLink) { null }
        )
        var redirectCalled = false
        val ordinary = resolveSearchSubmitActionWithRedirect("番剧") {
            redirectCalled = true
            BilibiliNavigationTarget.Video("BV1xx411c7mD")
        }
        assertEquals(SearchSubmitAction.OpenSearch("番剧"), ordinary)
        assertFalse(redirectCalled)
    }

    @Test
    fun `search link resolved from short url searches decoded keyword`() = runBlocking {
        assertEquals(
            SearchSubmitAction.OpenSearch("猫"),
            resolveSearchSubmitActionWithRedirect("https://b23.tv/search") {
                BilibiliNavigationTarget.Search("猫")
            }
        )
    }
}
