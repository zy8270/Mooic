package com.rcmiku.ncmapi.api.comment

import com.rcmiku.ncmapi.api.requestNetease
import com.rcmiku.ncmapi.model.Comment
import com.rcmiku.ncmapi.model.CommentResponse
import com.rcmiku.ncmapi.utils.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

object CommentApi {

    /**
     * 获取歌曲评论，兼容新版 data 包裹和旧版顶层返回结构。
     * 先尝试 /comment/new，失败时回退到 /comment/music。
     */
    suspend fun songComments(
        songId: Long,
        pageSize: Int = 20,
        pageNo: Int = 1,
        sortType: Int = 3
    ): Result<CommentResponse> {
        val newApiResult = runCatching {
            normalize(
                requestNetease(
                    "/comment/new",
                    mapOf(
                        "type" to 0,
                        "id" to songId,
                        "pageSize" to pageSize,
                        "pageNo" to pageNo,
                        "sortType" to sortType,
                    )
                )
            )
        }
        if (newApiResult.isSuccess) {
            return newApiResult
        }

        return runCatching {
            normalize(requestNetease("/comment/music", mapOf("id" to songId)))
        }
    }

    internal fun normalize(body: String): CommentResponse {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"] as? JsonObject

        // 旧版顶层结构
        val topComments = (root["comments"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.map { json.decodeFromJsonElement(Comment.serializer(), it) }
            .orEmpty()
        val topHotComments = (root["hotComments"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.map { json.decodeFromJsonElement(Comment.serializer(), it) }
            .orEmpty()
        val topTotal = (root["total"] as? JsonPrimitive)?.longOrNull ?: 0

        // 新版 data 包裹结构
        val dataComments = (data?.get("comments") as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.map { json.decodeFromJsonElement(Comment.serializer(), it) }
            .orEmpty()
        val dataHotComments = (data?.get("hotComments") as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.map { json.decodeFromJsonElement(Comment.serializer(), it) }
            .orEmpty()
        val dataCurrentComment = (data?.get("currentComment") as? JsonObject)
            ?.let { json.decodeFromJsonElement(Comment.serializer(), it) }
        val dataTotal = (data?.get("totalCount") as? JsonPrimitive)?.longOrNull
            ?: (data?.get("total") as? JsonPrimitive)?.longOrNull
            ?: 0
        val dataHasMore = (data?.get("hasMore") as? JsonPrimitive)?.booleanOrNull ?: false

        val comments = dataComments.ifEmpty { topComments }
        val hotComments = buildList {
            addAll(topHotComments)
            addAll(dataHotComments)
            dataCurrentComment?.let { add(it) }
        }
        val total = if (dataTotal > 0) dataTotal else topTotal

        return CommentResponse(
            comments = comments,
            hotComments = hotComments,
            total = total,
            hasMore = dataHasMore
        )
    }
}
