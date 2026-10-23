package com.android.purebilibili.feature.video.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.data.repository.loadCommentReplyPreview
import kotlinx.coroutines.flow.Flow

internal data class ReplyPreviewState(
    val collapsedLimit: Int,
    val replies: List<ReplyItem>,
)

/** Shared by video, dynamic and other root-comment rows; offscreen rows do not fetch. */
@Composable
internal fun rememberReplyPreviewState(
    item: ReplyItem,
    enabled: Boolean,
): ReplyPreviewState {
    val context = LocalContext.current
    val settingFlow: Flow<Int?> = remember(context) {
        SettingsManager.getCommentCollapsedReplyPreviewLimit(context)
    }
    val configuredLimit by settingFlow.collectAsStateWithLifecycle(initialValue = null)
    val collapsedLimit = configuredLimit ?: SettingsManager.DEFAULT_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT
    var replies by remember(item.oid, item.replyType, item.rpid, item.replies) {
        mutableStateOf(item.replies.orEmpty())
    }
    LaunchedEffect(item.oid, item.replyType, item.rpid, item.replies, item.count, item.rcount, configuredLimit, enabled) {
        // Wait for DataStore rather than fetching the default before the real preference arrives.
        val limit = configuredLimit
        if (enabled && limit != null) {
            replies = loadCommentReplyPreview(item, limit, replies)
        }
    }
    return remember(collapsedLimit, replies) { ReplyPreviewState(collapsedLimit, replies) }
}
