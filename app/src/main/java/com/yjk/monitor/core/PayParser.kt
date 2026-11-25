package com.yjk.monitor.core

/**
 * 「通用码支付」通知解析器。
 *
 * 思路：不绑定任何一家支付机构，而是用「关键词 + 金额正则」的通用规则去识别
 * 收款类通知。这样微信、支付宝、云闪付、各家码商 App 的通知都能吃下来，
 * 遇到特殊格式只要在设置里加关键词即可。
 */
object PayParser {

    // 金额：优先带货币符号/单位，其次跟"收款/到账"等词相邻，最后兜底小数
    private val AMOUNT_PATTERNS = listOf(
        Regex("""(?:￥|¥|RMB|CNY)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""),
        Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:元|圆|块钱)"""),
        Regex("""(?:金额|收款|到账|付款|支付|收入)[为是]?[:：\s]*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""),
        Regex("""([0-9]{1,7}\.[0-9]{2})""")
    )

    private val PAYER_PATTERNS = listOf(
        Regex("""([^\s，,。.:：、|]{1,20})(?:向你|向您|给你|给您)(?:付款|转账|支付)"""),
        Regex("""(?:来自|付款人|付款方|买家|客户)[:：\s]*([^\s，,。;；、|]{1,20})""")
    )

    private val ORDER_PATTERNS = listOf(
        Regex("""(?:订单号|交易号|流水号|单号|商户单号)[:：\s]*([A-Za-z0-9_\-]{6,40})""")
    )

    /** 提取结果清洗：去掉首尾标点、括号、引号 */
    private val TRIM_CHARS = charArrayOf(
        ':', '：', '，', ',', '。', '.', '、', '|',
        '（', '(', '）', ')', '【', '】', '[', ']',
        '"', '“', '”', ' ', '　'
    )

    /** 根据包名推断来源渠道名 */
    fun channelOf(pkg: String, appName: String): String = when {
        pkg == "com.tencent.mm" -> "微信"
        pkg.contains("alipay", true) -> "支付宝"
        pkg.contains("unionpay", true) || pkg.contains("95516") -> "云闪付"
        pkg == "com.tencent.mobileqq" -> "QQ"
        pkg.contains("tenpay", true) -> "财付通"
        else -> appName.ifEmpty { pkg }
    }

    /**
     * 只负责把通知原文打包成一条记录。
     *
     * **不做任何业务判定**：不判关键词、不判金额、不判排除词、不做去重。
     * 是否是收款、金额多少，全部由支付平台拿到原文后自己判断。
     * 这里解析出的金额只用于日志展示，解析不到就是 -1。
     */
    fun parse(
        pkg: String,
        appName: String,
        title: String,
        text: String,
        postedAt: Long
    ): PayRecord? {
        // 完全没有任何文本的通知没有上报价值（比如纯图标通知）
        if (title.isBlank() && text.isBlank()) return null

        val body = (if (title.isBlank()) text else "$title\n$text").trim()
        val amount = parseAmount(body)

        return PayRecord(
            id = System.currentTimeMillis() * 1000L + (System.nanoTime() % 1000),
            time = if (postedAt > 0) postedAt else System.currentTimeMillis(),
            pkg = pkg,
            appName = appName,
            channelName = channelOf(pkg, appName),
            title = title,
            text = text,
            amount = amount,
            payer = firstGroup(PAYER_PATTERNS, body),
            orderNo = firstGroup(ORDER_PATTERNS, body),
            status = RecordStatus.PENDING
        )
    }

    /** 从文本里抽金额，抽不到返回 -1 */
    fun parseAmount(text: String): Double {
        for (p in AMOUNT_PATTERNS) {
            for (m in p.findAll(text)) {
                val raw = m.groupValues[1].replace(",", "")
                val v = raw.toDoubleOrNull() ?: continue
                if (v in 0.01..9_999_999.0) return v
            }
        }
        return -1.0
    }

    private fun firstGroup(patterns: List<Regex>, text: String): String {
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val v = m.groupValues[1].trim(*TRIM_CHARS)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

}
