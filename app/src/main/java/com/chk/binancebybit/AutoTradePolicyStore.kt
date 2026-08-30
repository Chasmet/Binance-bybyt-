package com.chk.binancebybit

import android.content.Context
import java.time.LocalDate

class AutoTradePolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun allowBotRules(): Boolean = prefs.getBoolean(KEY_BOT_RULES, true)
    fun setAllowBotRules(value: Boolean) = prefs.edit().putBoolean(KEY_BOT_RULES, value).apply()

    fun allowChatGptProposals(): Boolean = prefs.getBoolean(KEY_CHATGPT, false)
    fun setAllowChatGptProposals(value: Boolean) = prefs.edit().putBoolean(KEY_CHATGPT, value).apply()

    fun maxOrderUsdc(): Double = prefs.getFloat(KEY_MAX_ORDER, 10f).toDouble().coerceIn(1.01, 10.0)
    fun setMaxOrderUsdc(value: Double) = prefs.edit().putFloat(KEY_MAX_ORDER, value.coerceIn(1.01, 10.0).toFloat()).apply()

    fun dailyCapUsdc(): Double = prefs.getFloat(KEY_DAILY_CAP, 30f).toDouble().coerceIn(5.0, 200.0)
    fun setDailyCapUsdc(value: Double) = prefs.edit().putFloat(KEY_DAILY_CAP, value.coerceIn(5.0, 200.0).toFloat()).apply()

    fun maxOrdersPerDay(): Int = prefs.getInt(KEY_MAX_ORDERS, 3).coerceIn(1, 20)
    fun setMaxOrdersPerDay(value: Int) = prefs.edit().putInt(KEY_MAX_ORDERS, value.coerceIn(1, 20)).apply()

    fun todayNotional(): Double {
        resetIfNewDay()
        return prefs.getFloat(KEY_TODAY_NOTIONAL, 0f).toDouble()
    }

    fun todayOrders(): Int {
        resetIfNewDay()
        return prefs.getInt(KEY_TODAY_ORDERS, 0)
    }

    fun canExecute(proposal: TradeProposal): Decision {
        resetIfNewDay()
        if (!enabled()) return Decision(false, "Auto-Trade désactivé")
        if (proposal.orderType != "LIMIT") return Decision(false, "Auto-Trade limité aux ordres LIMIT")
        if (proposal.quoteAmountUsdc <= 1.0 || proposal.quoteAmountUsdc > maxOrderUsdc() + 1e-9) {
            return Decision(false, "Montant hors plafond Auto-Trade")
        }
        val isBot = proposal.source.lowercase().contains("bot-chk")
        val isChat = proposal.source.lowercase().contains("chatgpt") || proposal.source.lowercase().contains("workspace")
        if (isBot && !allowBotRules()) return Decision(false, "Auto-exécution des règles Bot désactivée")
        if (!isBot && isChat && !allowChatGptProposals()) return Decision(false, "Auto-confirmation ChatGPT désactivée")
        if (!isBot && !isChat) return Decision(false, "Source de proposition non autorisée")
        if (todayOrders() >= maxOrdersPerDay()) return Decision(false, "Limite quotidienne d'ordres atteinte")
        if (todayNotional() + proposal.quoteAmountUsdc > dailyCapUsdc() + 1e-9) return Decision(false, "Plafond quotidien USDC atteint")
        return Decision(true, "OK")
    }

    fun recordExecuted(proposal: TradeProposal) {
        resetIfNewDay()
        prefs.edit()
            .putInt(KEY_TODAY_ORDERS, todayOrders() + 1)
            .putFloat(KEY_TODAY_NOTIONAL, (todayNotional() + proposal.quoteAmountUsdc).toFloat())
            .apply()
    }

    fun resetDailyCounters() {
        prefs.edit()
            .putString(KEY_DAY, LocalDate.now().toString())
            .putInt(KEY_TODAY_ORDERS, 0)
            .putFloat(KEY_TODAY_NOTIONAL, 0f)
            .apply()
    }

    private fun resetIfNewDay() {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DAY, "") != today) resetDailyCounters()
    }

    data class Decision(val allowed: Boolean, val reason: String)

    companion object {
        private const val PREFS = "chk_auto_trade_v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BOT_RULES = "allow_bot_rules"
        private const val KEY_CHATGPT = "allow_chatgpt_proposals"
        private const val KEY_MAX_ORDER = "max_order_usdc"
        private const val KEY_DAILY_CAP = "daily_cap_usdc"
        private const val KEY_MAX_ORDERS = "max_orders_per_day"
        private const val KEY_DAY = "counter_day"
        private const val KEY_TODAY_NOTIONAL = "today_notional"
        private const val KEY_TODAY_ORDERS = "today_orders"
    }
}
