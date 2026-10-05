package com.example.vpn.subscription

/**
 * The VIP servers, served by the Telegram bot's Worker (`tools/telegram-bot`, route `/vip`). The admin
 * adds and removes them with /addvip and /delvip; the VIP screen adds the subscription on first open.
 */
object VipSubscription {
    const val NAME = "MAXIMUS VIP"
    const val URL = "https://maximus-bot.drpouriafx.workers.dev/vip"
}
