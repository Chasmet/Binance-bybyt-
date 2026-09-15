package com.chk.binancebybit

import android.content.Context
import java.time.Instant
import java.time.LocalDate

enum class CancelAutomationMode {
    ANALYSIS_ONLY,
    EXECUTION_ENABLED
}

class AutoTradePolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // Upgrade the former per-order ceiling once; keep smaller custom limits and daily budgets.
        synchronized(AutoTradePolicyStore::class.java) {
            if (!prefs.getBoolean("limit_30_migrated", false)) {
                val edit = prefs.edit().putBoolean("limit_30_migrated", true)
                if (prefs.getFloat(KEY_MAX_ORDER, 10f) == 10f) edit.putFloat(KEY_MAX_ORDER, 30f)
                if (prefs.getInt(KEY_MAX_ORDERS, 3) == 3) edit.putInt(KEY_MAX_ORDERS, 5)
                edit.commit()
            }
            // Safety migration: existing cancel permission never silently enables execution after
            // this version. The user must explicitly leave ANALYSIS_ONLY in the settings screen.
            if (!prefs.contains(KEY_CANCEL_MODE)) {
                prefs.edit().putString(KEY_CANCEL_MODE, CancelAutomationMode.ANALYSIS_ONLY.name).commit()
            }
        }
    }

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(value: Boolean) {
        val edit = prefs.edit().putBoolean(KEY_ENABLED, value)
        if (value) edit.putLong(KEY_ARMED_AT, System.currentTimeMillis())
        edit.apply()
    }
    fun armedAt(): Long = prefs.getLong(KEY_ARMED_AT, Long.MAX_VALUE)

    fun allowBotRules(): Boolean = prefs.getBoolean(KEY_BOT_RULES, true)
    fun setAllowBotRules(value: Boolean) = prefs.edit().putBoolean(KEY_BOT_RULES, value).apply()

    fun allowChatGptProposals(): Boolean = prefs.getBoolean(KEY_CHATGPT, false)
    fun setAllowChatGptProposals(value: Boolean) = prefs.edit().putBoolean(KEY_CHATGPT, value).apply()

    fun allowCancelReplace(): Boolean = prefs.getBoolean(KEY_CANCEL_REPLACE, false)
    fun setAllowCancelReplace(value: Boolean) {
        val wasEnabled = allowCancelReplace()
        val edit = prefs.edit().putBoolean(KEY_CANCEL_REPLACE, value)
        if (value && !wasEnabled) edit.putLong(KEY_CANCEL_REPLACE_ARMED_AT, System.currentTimeMillis())
        if (!value) edit.putString(KEY_CANCEL_MODE, CancelAutomationMode.ANALYSIS_ONLY.name)
        edit.apply()
    }
    fun cancelReplaceArmedAt(): Long = prefs.getLong(KEY_CANCEL_REPLACE_ARMED_AT, Long.MAX_VALUE)

    fun cancelAutomationMode(): CancelAutomationMode = runCatching {
        CancelAutomationMode.valueOf(prefs.getString(KEY_CANCEL_MODE, CancelAutomationMode.ANALYSIS_ONLY.name)!!)
    }.getOrDefault(CancelAutomationMode.ANALYSIS_ONLY)

    fun setCancelAutomationMode(mode: CancelAutomationMode) {
        val edit = prefs.edit().putString(KEY_CANCEL_MODE, mode.name)
        if (mode == CancelAutomationMode.EXECUTION_ENABLED) {
            edit.putLong(KEY_CANCEL_EXECUTION_ARMED_AT, System.currentTimeMillis())
        }
        edit.apply()
    }

    fun cancelExecutionArmedAt(): Long = prefs.getLong(KEY_CANCEL_EXECUTION_ARMED_AT, Long.MAX_VALUE)

    fun maxOrderUsdc(): Double = prefs.getFloat(KEY_MAX_ORDER, 30f).toDouble().coerceIn(1.01, 30.0)
    fun setMaxOrderUsdc(value: Double) = prefs.edit().putFloat(KEY_MAX_ORDER, value.coerceIn(1.01, 30.0).toFloat()).apply()

    fun dailyCapUsdc(): Double = prefs.getFloat(KEY_DAILY_CAP, 30f).toDouble().coerceIn(5.0, 200.0)
    fun setDailyCapUsdc(value: Double) = prefs.edit().putFloat(KEY_DAILY_CAP, value.coerceIn(5.0, 200.0).toFloat()).apply()

    fun maxOrdersPerDay(): Int = prefs.getInt(KEY_MAX_ORDERS, 5).coerceIn(1, 20)
    fun setMaxOrdersPerDay(value: Int) = prefs.edit().putInt(KEY_MAX_ORDERS, value.coerceIn(1, 20)).apply()

    fun todayNotional(): Double {
        resetIfNewDay()
        return prefs.getFloat(KEY_TODAY_NOTIONAL, 0f).toDouble()
    }

    fun todayOrders(): Int {
        resetIfNewDay()
        return prefs.getInt(KEY_TODAY_ORDERS, 0)
    }

    fun canAutoCancel(proposal: CancelProposal): Decision {
        if (!enabled()) return Decision(false, "Auto-Trade désactivé")
        if (!allowCancelReplace()) return Decision(false, "Annulation/remplacement automatique désactivé")
        if (cancelAutomationMode() != CancelAutomationMode.EXECUTION_ENABLED) {
            return Decision(false, "ANALYSIS_ONLY : aucune annulation réelle autorisée")
        }
        if (!proposal.hasExplicitIntent) return Decision(false, "Intention CANCEL/REPLACE explicite requise")
        val createdAt = proposal.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return Decision(false, "Date de proposition d'annulation absente")
        val minimumCreatedAt = maxOf(armedAt(), cancelReplaceArmedAt(), cancelExecutionArmedAt())
        if (createdAt < minimumCreatedAt) {
            return Decision(false, "Proposition d'annulation antérieure à l'autorisation d'exécution")
        }
        if (!proposal.symbol.matches(Regex("^[A-Z0-9]{2,20}USDC$"))) {
            return Decision(false, "Annulation automatique limitée au Spot CRYPTO/USDC")
        }
        if (proposal.targetOrderId.isBlank()) return Decision(false, "Order ID cible absent")
        val expired = proposal.expiresAt?.let {
            runCatching { Instant.parse(it).toEpochMilli() <= System.currentTimeMillis() }.getOrDefault(false)
        } ?: false
        if (expired) return Decision(false, "Proposition d'annulation expirée")
        return Decision(true, "OK")
    }

    fun canExecute(proposal: TradeProposal, reserved: Boolean = false): Decision {
        resetIfNewDay()
        if (!enabled()) return Decision(false, "Auto-Trade désactivé")
        val createdAt = proposal.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return Decision(false, "Date de proposition absente")
        if (createdAt < armedAt()) return Decision(false, "Proposition antérieure à l'activation Auto-Trade")
        if (proposal.orderType != "LIMIT") return Decision(false, "Auto-Trade limité aux ordres LIMIT")
        if (!proposal.quoteAmountUsdc.isFinite() || proposal.quoteAmountUsdc <= 1.0 || proposal.quoteAmountUsdc > maxOrderUsdc() + 1e-9) {
            return Decision(false, "Montant hors plafond Auto-Trade")
        }
        val source = proposal.source.lowercase()
        val isBot = source.contains("bot-chk")
        val isChat = source.contains("chatgpt") || source.contains("workspace")
        val isCancelReplacement = source.startsWith("cancel-replacement:")
        if (isBot && !allowBotRules()) return Decision(false, "Auto-exécution des règles Bot désactivée")
        if (!isBot && isChat && !allowChatGptProposals()) return Decision(false, "Auto-confirmation ChatGPT désactivée")
        if (isCancelReplacement && !allowCancelReplace()) return Decision(false, "Remplacement automatique désactivé")
        if (!isBot && !isChat && !isCancelReplacement) return Decision(false, "Source de proposition non autorisée")
        val alreadyReserved = reserved && prefs.getStringSet("counted_ids", emptySet())!!.contains(proposal.id)
        if (!alreadyReserved && todayOrders() >= maxOrdersPerDay()) return Decision(false, "Limite quotidienne d'ordres atteinte")
        if (!alreadyReserved && todayNotional() + proposal.quoteAmountUsdc > dailyCapUsdc() + 1e-9) return Decision(false, "Plafond quotidien USDC atteint")
        return Decision(true, "OK")
    }

    fun recordExecuted(proposal: TradeProposal) {
        resetIfNewDay()
        val ids = prefs.getStringSet("counted_ids", emptySet())!!.toMutableSet()
        if (!ids.add(proposal.id)) return
        prefs.edit()
            .putStringSet("counted_ids", ids)
            .putInt(KEY_TODAY_ORDERS, todayOrders() + 1)
            .putFloat(KEY_TODAY_NOTIONAL, (todayNotional() + proposal.quoteAmountUsdc).toFloat())
            .commit()
    }

    fun releaseReservation(proposal: TradeProposal) {
        resetIfNewDay()
        val ids = prefs.getStringSet("counted_ids", emptySet())!!.toMutableSet()
        if (!ids.remove(proposal.id)) return
        prefs.edit().putStringSet("counted_ids", ids)
            .putInt(KEY_TODAY_ORDERS, (todayOrders() - 1).coerceAtLeast(0))
            .putFloat(KEY_TODAY_NOTIONAL, (todayNotional() - proposal.quoteAmountUsdc).coerceAtLeast(0.0).toFloat())
            .commit()
    }

    fun resetDailyCounters() {
        prefs.edit()
            .putString(KEY_DAY, LocalDate.now().toString())
            .putStringSet("counted_ids", emptySet())
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
        private const val KEY_ARMED_AT = "armed_at"
        private const val KEY_BOT_RULES = "allow_bot_rules"
        private const val KEY_CHATGPT = "allow_chatgpt_proposals"
        private const val KEY_CANCEL_REPLACE = "allow_cancel_replace"
        private const val KEY_CANCEL_REPLACE_ARMED_AT = "cancel_replace_armed_at"
        private const val KEY_CANCEL_MODE = "cancel_automation_mode"
        private const val KEY_CANCEL_EXECUTION_ARMED_AT = "cancel_execution_armed_at"
        private const val KEY_MAX_ORDER = "max_order_usdc"
        private const val KEY_DAILY_CAP = "daily_cap_usdc"
        private const val KEY_MAX_ORDERS = "max_orders_per_day"
        private const val KEY_DAY = "counter_day"
        private const val KEY_TODAY_NOTIONAL = "today_notional"
        private const val KEY_TODAY_ORDERS = "today_orders"
    }
}
