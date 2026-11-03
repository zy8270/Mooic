package com.rcmiku.music.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.rcmiku.ncmapi.utils.CookieKeys
import com.rcmiku.ncmapi.utils.CookieProvider

fun reportLikeFailure(
    context: Context,
    like: Boolean,
    songId: Long,
    userId: Long,
    error: Throwable
) {
    val action = if (like) "喜欢" else "取消喜欢"
    val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
    val cookie = CookieProvider.getCookieMap()
    val details = buildString {
        appendLine("操作：$action")
        appendLine("歌曲 ID：$songId")
        appendLine("用户 ID：$userId")
        appendLine("API 地址：https://interface.music.163.com")
        appendLine("MUSIC_U：${cookie.containsKey(CookieKeys.MUSIC_U)}")
        appendLine("__csrf：${cookie.containsKey(CookieKeys.CSRF)}")
        appendLine("deviceId：${cookie.containsKey(CookieKeys.DEVICE_ID)}")
        appendLine("os：${cookie[CookieKeys.OS].orEmpty()}")
        appendLine("appver：${cookie[CookieKeys.APP_VER].orEmpty()}")
        appendLine("错误类型：${error.javaClass.name}")
        append("错误原因：$reason")
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("喜欢歌曲失败信息", details))
    Toast.makeText(
        context,
        "${action}失败，详细错误已复制到剪贴板",
        Toast.LENGTH_LONG
    ).show()
}
