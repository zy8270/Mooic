package com.rcmiku.ncmapi.api.player

import com.rcmiku.ncmapi.api.NeteaseCrypto
import com.rcmiku.ncmapi.api.UNBLOCK_BASE_URL
import com.rcmiku.ncmapi.api.apiClient
import com.rcmiku.ncmapi.api.apiGet
import com.rcmiku.ncmapi.api.requireSuccess
import com.rcmiku.ncmapi.model.LyricResponse
import com.rcmiku.ncmapi.model.SongUrl
import com.rcmiku.ncmapi.model.SongUrlResponse
import com.rcmiku.ncmapi.utils.CookieKeys
import com.rcmiku.ncmapi.utils.CookieProvider
import com.rcmiku.ncmapi.utils.NeteaseClientConfig
import com.rcmiku.ncmapi.utils.json as apiJson
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

object PlayerApi {
    private val unblockSources = listOf(
        "byfuns",
        "ddyr",
        "gdmusic",
        "msls",
        "oi",
        "qijieya",
        "unm"
    )

    private val anonymousDeviceId = UUID.randomUUID().toString().replace("-", "")

    suspend fun songPlayUrlV1(songId: String, songLevel: SongLevel = SongLevel.STANDARD): Result<SongUrlResponse> {
        val realId = parseSongId(songId).id
        val officialResult = requestOfficialUrl(realId, songLevel)
        return if (officialResult.hasPlayableUrl()) officialResult else tryUnblockUrl(realId)
    }

    private data class ParsedSongId(
        val id: String,
        val fee: Int = 0,
        val pl: Int? = null,
        val dl: Int? = null,
        val fl: Int? = null,
        val st: Int? = null
    ) {
        fun shouldUseApiUnblock(): Boolean =
            fee == 1 || fee == 4 || fee == 8 || pl == 0 || (st != null && st < 0)
    }

    private fun parseSongId(raw: String): ParsedSongId {
        val queryIndex = raw.indexOf('?')
        if (queryIndex == -1) return ParsedSongId(raw)
        val id = raw.substring(0, queryIndex)
        val query = raw.substring(queryIndex + 1)
        val params = query.split("&").associate {
            val eq = it.indexOf('=')
            if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else it to ""
        }
        val fee = params["fee"]?.toIntOrNull() ?: 0
        val pl = params["pl"]?.toIntOrNull()
        val dl = params["dl"]?.toIntOrNull()
        val fl = params["fl"]?.toIntOrNull()
        val st = params["st"]?.toIntOrNull()
        return ParsedSongId(id = id, fee = fee, pl = pl, dl = dl, fl = fl, st = st)
    }

    private fun Result<SongUrlResponse>.hasPlayableUrl(): Boolean =
        getOrNull()?.data?.any { it.hasFullPlayableUrl() } == true

    private fun SongUrl.hasFullPlayableUrl(): Boolean {
        val playableUrl = url?.takeIf { it.isNotBlank() } ?: return false
        if (isLikelyTrialUrl(playableUrl)) return false
        if (freeTrialInfo != null && freeTrialInfo !is JsonNull) return false
        if (time in 1..60_000) return false
        return true
    }

    private suspend fun requestOfficialUrl(
        songId: String,
        songLevel: SongLevel
    ): Result<SongUrlResponse> = runCatching {
        val path = "/api/song/enhance/player/url/v1"
        val cookies = buildMap {
            putAll(NeteaseClientConfig.cookieOverrides)
            put(CookieKeys.DEVICE_ID, anonymousDeviceId)
            putAll(CookieProvider.getCookieMap())
        }
        val csrf = cookies[CookieKeys.CSRF].orEmpty()
        val headerJson = buildJsonObject {
            listOf(
                "osver", "deviceId", "os", "appver", "versioncode",
                "mobilename", "resolution", "channel"
            ).forEach { key ->
                cookies[key]?.let { put(key, it) }
            }
            put("buildver", (System.currentTimeMillis() / 1000).toString())
            put("requestId", "${System.currentTimeMillis()}_${(0..9999).random()}")
            put("__csrf", csrf)
            cookies[CookieKeys.MUSIC_U]?.let { put(CookieKeys.MUSIC_U, it) }
        }
        val requestJson = buildJsonObject {
            put("ids", "[$songId]")
            put("level", songLevel.value)
            put("encodeType", "flac")
            put("header", headerJson)
        }.toString()
        val fields = NeteaseCrypto.eapi(path, requestJson)
        val response = apiClient.request("https://interface.music.163.com/eapi/song/enhance/player/url/v1") {
            method = HttpMethod.Post
            contentType(ContentType.Application.FormUrlEncoded)
            header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            setBody(fields.entries.joinToString("&") { (key, value) ->
                "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
            })
        }
        val responseBody = response.bodyAsText()
        response.requireSuccess(responseBody)
        apiJson.decodeFromString<SongUrlResponse>(responseBody)
    }

    private suspend fun tryUnblockUrl(songId: String): Result<SongUrlResponse> =
        tryMatchUrl("${UNBLOCK_BASE_URL.trimEnd('/')}/match", songId)

    private suspend fun tryMatchUrl(endpoint: String, songId: String): Result<SongUrlResponse> {
        var lastError: Exception? = null
        unblockSources.forEach { source ->
            try {
                requestMatchedUrl(endpoint, songId, source)?.let { data ->
                    return Result.success(
                        SongUrlResponse(
                            data = listOf(
                                SongUrl(
                                    id = songId.toLongOrNull() ?: 0L,
                                    url = data.url,
                                    br = data.br
                                )
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: Exception("No full unlocked URL found"))
    }

    private suspend fun requestMatchedUrl(
        endpoint: String,
        songId: String,
        source: String
    ): UnblockData? {
        return try {
            val response = apiClient.request(endpoint) {
                method = HttpMethod.Get
                parameter("id", songId)
                parameter("source", source)
            }
            if (response.status.isSuccess()) {
                parseUnblockResponse(response.bodyAsText())
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class UnblockData(val url: String, val br: Int)

    private fun parseUnblockResponse(body: String): UnblockData? {
        return try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val code = jsonObj["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 200) return null

            val proxyUrl = jsonObj["proxyUrl"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val data = extractMatchedData(jsonObj["data"])
            val playableUrl = proxyUrl ?: data?.url?.takeIf { it.isNotBlank() }
            playableUrl?.let { UnblockData(it, data?.br ?: 320000) }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractMatchedData(dataElement: JsonElement?): UnblockData? {
        val dataObj = dataElement as? JsonObject
        val dataPrimitive = dataElement as? JsonPrimitive
        val dataArray = dataElement as? JsonArray

        dataObj?.let { return extractUrlFromObject(it) }

        dataPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && !isLikelyTrialUrl(it) }
            ?.let { return UnblockData(it, 320000) }

        dataArray?.forEach { element ->
            when (element) {
                is JsonObject -> extractUrlFromObject(element)?.let { return it }
                is JsonPrimitive -> element.contentOrNull
                    ?.takeIf { it.isNotBlank() && !isLikelyTrialUrl(it) }
                    ?.let { return UnblockData(it, 320000) }
                else -> Unit
            }
        }

        return null
    }

    private fun extractUrlFromObject(dataObj: JsonObject): UnblockData? {
        if (dataObj["freeTrialInfo"] != null && dataObj["freeTrialInfo"] !is JsonNull) return null

        val url = dataObj["url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && !isLikelyTrialUrl(it) }
        val time = dataObj["time"]?.jsonPrimitive?.longOrNull
        val br = dataObj["br"]?.jsonPrimitive?.intOrNull
            ?: dataObj["bitrate"]?.jsonPrimitive?.intOrNull
            ?: 320000

        if (time != null && time in 1..60_000) return null
        return url?.let { UnblockData(it, br) }
    }

    private fun isLikelyTrialUrl(url: String): Boolean =
        url.contains("musicrep-ts", ignoreCase = true)

    suspend fun songLyric(musicId: Long): Result<LyricResponse> =
        apiGet("/lyric", mapOf("id" to musicId))
}
