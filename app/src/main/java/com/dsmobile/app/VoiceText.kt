package com.dsmobile.app

/**
 * 语音只念“说出口”的内容，去掉括号里的动作/神态描述。
 * 气泡里的转文字仍保留原文，只有 TTS 使用这个函数。
 */
fun stripSpokenActions(text: String): String {
    val out = StringBuilder()
    var depth = 0
    for (ch in text) {
        when (ch) {
            '（', '(' -> depth++
            '）', ')' -> if (depth > 0) depth-- else out.append(ch)
            else -> if (depth == 0) out.append(ch)
        }
    }
    return out.toString()
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
