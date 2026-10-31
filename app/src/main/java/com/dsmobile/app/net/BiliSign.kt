/**
 * B 站 wbi 签名：mixinKey 重排 + 参数排序 + MD5。
 *
 * 从 ApiClient 里拆出来的**纯算法**部分，不碰网络——原来这两段挤在一个
 * 1000 行的类里，改签名规则时很容易碰坏别的请求。
 */
package com.dsmobile.app.net

import org.json.JSONObject

/** wbi 的字符重排表（B 站固定值）。 */
internal val BILI_MIXIN_TAB = intArrayOf(
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
    61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
    36, 20, 34, 44, 52,
)

/** 从 nav 接口返回的 wbi 图片名算出 mixinKey。 */
internal fun biliMixinKeyFrom(wbi: JSONObject): String? = runCatching {
    val img = wbi.optString("img_url").substringAfterLast('/').substringBefore('.')
    val sub = wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
    val orig = img + sub
    BILI_MIXIN_TAB.map { orig.getOrNull(it)?.toString() ?: "" }.joinToString("").take(32)
}.getOrNull()

/** 给参数加时间戳、清理非法字符、排序，再拼上 w_rid。 */
internal fun biliSign(params: Map<String, String>, mixinKey: String): String {
    val wts = (System.currentTimeMillis() / 1000).toString()
    val cleaned = (params + ("wts" to wts))
        .mapValues { it.value.replace(Regex("[!'()*]"), "") }
        .toSortedMap()
    val query = cleaned.entries.joinToString("&") {
        "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
    }
    return "$query&w_rid=${md5(query + mixinKey)}"
}

internal fun md5(value: String): String {
    val digest = java.security.MessageDigest.getInstance("MD5").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
