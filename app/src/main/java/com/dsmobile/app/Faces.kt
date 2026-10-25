package com.dsmobile.app

/**
 * QQ/Worker 下发的 [face:名称] 或 [face:数字] 转成 emoji。
 * 未收录的名字保留成 [名称]，不静默丢失。
 */
private val FACE_REGEX = Regex("""\[face:([^\]]+)]""", RegexOption.IGNORE_CASE)

private val FACE_BY_NAME = mapOf(
    "微笑" to "🙂", "可爱" to "🥰", "偷笑" to "🤭", "呲牙" to "😁",
    "调皮" to "😜", "色" to "😍", "害羞" to "☺️", "得意" to "😏",
    "发怒" to "😠", "白眼" to "🙄", "撇嘴" to "😒", "惊讶" to "😮",
    "难过" to "😔", "流泪" to "😢", "大哭" to "😭", "睡" to "😴",
    "困" to "😪", "哈欠" to "🥱", "冷汗" to "😅", "抓狂" to "😫",
    "晕" to "😵", "衰" to "😩", "委屈" to "🥺", "快哭了" to "🥹",
    "可怜" to "🥺", "思考" to "🤔", "嘘" to "🤫", "赞" to "👍",
    "强" to "👍", "弱" to "👎", "踩" to "👎", "ok" to "👌",
    "胜利" to "✌️", "鼓掌" to "👏", "握手" to "🤝", "抱拳" to "🙏",
    "拥抱" to "🤗", "爱心" to "❤️", "心碎" to "💔", "玫瑰" to "🌹",
    "凋谢" to "🥀", "蛋糕" to "🎂", "啤酒" to "🍺", "咖啡" to "☕",
    "饭" to "🍚", "猪" to "🐷", "dog" to "🐶", "喵" to "🐱",
    "月亮" to "🌙", "太阳" to "☀️", "礼物" to "🎁", "庆祝" to "🎉",
    "烟花" to "🎆", "炸弹" to "💣", "刀" to "🔪", "闪电" to "⚡",
    "篮球" to "🏀", "足球" to "⚽", "乒乓" to "🏓", "便便" to "💩",
    "骷髅" to "💀", "差劲" to "👎", "NO" to "🙅", "OK" to "👌",
)

private val FACE_BY_ID = mapOf(
    "0" to "😮", "1" to "😒", "2" to "😍", "3" to "😳", "4" to "😏",
    "5" to "😢", "6" to "☺️", "7" to "🤐", "8" to "😴", "9" to "😭",
    "10" to "😅", "11" to "😠", "12" to "😜", "13" to "😁", "14" to "🙂",
    "15" to "😔", "16" to "😎", "17" to "😅", "18" to "😫", "19" to "🤮",
    "20" to "🤭", "21" to "🥰", "22" to "🙄", "23" to "😤", "24" to "😋",
    "25" to "😪", "26" to "😱", "27" to "😓", "28" to "😄", "29" to "😎",
    "30" to "💪", "31" to "❓", "32" to "🤫", "33" to "😵", "34" to "😖",
    "35" to "😩", "36" to "💀", "37" to "🔨", "38" to "👋", "39" to "😅",
    "40" to "🤏", "41" to "👏", "42" to "😳", "43" to "😏", "44" to "😤",
    "45" to "😤", "46" to "🥱", "47" to "🖕", "48" to "🥺", "49" to "🥹",
    "50" to "😈", "51" to "😘", "52" to "😱", "53" to "🥺", "54" to "🔪",
    "55" to "🍉", "56" to "🍺", "57" to "🏀", "58" to "🏓", "59" to "☕",
    "60" to "🍚", "61" to "🐷", "62" to "🌹", "63" to "🥀", "64" to "😘",
    "65" to "❤️", "66" to "💔", "67" to "🎂", "68" to "⚡", "69" to "💣",
    "70" to "🔪", "71" to "⚽", "72" to "🐞", "73" to "💩", "74" to "🌙",
    "75" to "☀️", "76" to "🎁", "77" to "🤗", "78" to "👍", "79" to "👎",
    "80" to "🤝", "81" to "✌️", "82" to "🙏", "83" to "💋", "84" to "👊",
    "85" to "👎", "86" to "💗", "87" to "🙅", "88" to "👌", "89" to "💕",
    "90" to "😘", "91" to "💃", "92" to "🥶", "93" to "😤", "94" to "🌀",
    "95" to "🙇", "96" to "↩️", "97" to "🤸", "98" to "👋", "99" to "🤩",
    "100" to "🕺", "101" to "💋", "102" to "☯️", "103" to "☯️",
)

fun replaceFaceMarkers(text: String): String = FACE_REGEX.replace(text) { match ->
    val name = match.groupValues[1].trim()
    FACE_BY_NAME[name]
        ?: FACE_BY_NAME[name.lowercase()]
        ?: FACE_BY_ID[name]
        ?: "[$name]"
}
