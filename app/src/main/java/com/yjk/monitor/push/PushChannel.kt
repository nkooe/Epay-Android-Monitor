package com.yjk.monitor.push

/**
 * 推送通道。做成"通用"的关键：渠道可替换，正文可自定义模板。
 */
enum class PushChannel(
    val key: String,
    val label: String,
    val desc: String,
    val endpointHint: String,
    val secretHint: String,
    val extraHint: String
) {
    WEBHOOK(
        "webhook", "自定义 Webhook",
        "POST JSON 到你自己的服务器，可带 HMAC-SHA256 签名，最适合对接自有发货/记账系统",
        "https://your-server.com/api/pay/notify",
        "签名密钥（留空则不签名）",
        ""
    ),
    WECOM(
        "wecom", "企业微信机器人",
        "群机器人 Webhook，消息进群，手机端也能直接收到",
        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxxx",
        "", ""
    ),
    DINGTALK(
        "dingtalk", "钉钉机器人",
        "群机器人 Webhook，支持加签（安全设置选「加签」时填密钥）",
        "https://oapi.dingtalk.com/robot/send?access_token=xxxx",
        "加签密钥 SECxxxx（留空则不签）",
        ""
    ),
    SERVERCHAN(
        "serverchan", "Server酱",
        "推到微信服务号，个人免费额度足够日常收款提醒",
        "SCT 开头的 SendKey",
        "", ""
    ),
    BARK(
        "bark", "Bark（iOS）",
        "推到 iPhone，Bark App 直接弹通知",
        "https://api.day.app/你的DeviceKey",
        "", ""
    ),
    TELEGRAM(
        "telegram", "Telegram",
        "机器人推送到任意会话，适合境外/多端",
        "机器人 Token，如 123456789:AAExxxxx",
        "", "chat_id，如 123456789"
    ),
    MAPAY(
        "mapay", "码支付平台",
        "填平台地址 + 商户ID + 商户密钥，按通用格式 POST 过去（金额/原文/签名）",
        "https://你的平台/api/pay/notify",
        "商户密钥（用于 sign 签名）",
        "商户ID，如 10001"
    ),
    GET_TEMPLATE(
        "get", "GET 模板",
        "参数直接拼进 URL，兼容最老式的接口；可用 \${amount} \${time} \${source} 等占位符",
        "https://your-server.com/notify?amt=\${amount}&t=\${timestamp}",
        "", ""
    ),
    NONE(
        "none", "不推送（仅本地）",
        "只记录、只在悬浮窗和 App 内显示，不发任何网络请求",
        "", "", ""
    );

    companion object {
        fun of(key: String?): PushChannel = entries.firstOrNull { it.key == key } ?: WEBHOOK
    }
}
