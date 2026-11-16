package com.universalbrain.ai.domain.automation

import com.universalbrain.ai.core.monitor.DeviceMonitor
import com.universalbrain.ai.core.monitor.NetworkMonitor

/**
 * Condition/action rule engine. Pure decision function — the service layer
 * feeds real snapshots in and executes the returned actions.
 *
 * Conditions: BATTERY_TEMP_OVER, BATTERY_LEVEL_UNDER, RAM_AVAIL_UNDER,
 * NETWORK_LOST, NETWORK_RESTORED, WORKER_OFFLINE.
 * Actions: NOTIFY, PAUSE_MINING, RESUME_MINING, START_SERVICE, STOP_SERVICE.
 */
object RuleEngine {

    enum class ConditionType { BATTERY_TEMP_OVER, BATTERY_LEVEL_UNDER, RAM_AVAIL_UNDER, NETWORK_LOST, NETWORK_RESTORED, WORKER_OFFLINE }
    enum class ActionType { NOTIFY, PAUSE_MINING, RESUME_MINING, START_SERVICE, STOP_SERVICE }

    data class Snapshot(
        val vitals: DeviceMonitor.Vitals,
        val networkConnected: Boolean,
        val previousNetworkConnected: Boolean,
        val offlineWorkers: Int
    )

    data class EvalRule(
        val id: String,
        val name: String,
        val conditionType: String,
        val conditionValue: String,
        val actionType: String,
        val actionValue: String,
        val enabled: Boolean
    )

    data class TriggeredAction(
        val ruleId: String,
        val ruleName: String,
        val action: ActionType,
        val value: String,
        val detail: String
    )

    fun evaluate(rules: List<EvalRule>, snapshot: Snapshot): List<TriggeredAction> {
        val actions = mutableListOf<TriggeredAction>()
        rules.forEach { rule ->
            if (!rule.enabled) return@forEach
            val threshold = rule.conditionValue.toDoubleOrNull()
            val conditionMet = when (rule.conditionType) {
                ConditionType.BATTERY_TEMP_OVER.name ->
                    threshold?.let { snapshot.vitals.batteryTempC >= it } == true
                ConditionType.BATTERY_LEVEL_UNDER.name ->
                    threshold?.let { snapshot.vitals.batteryLevelPct <= it } == true
                ConditionType.RAM_AVAIL_UNDER.name ->
                    threshold?.let { snapshot.vitals.ramAvailMb <= it } == true
                ConditionType.NETWORK_LOST.name ->
                    !snapshot.networkConnected && snapshot.previousNetworkConnected
                ConditionType.NETWORK_RESTORED.name ->
                    snapshot.networkConnected && !snapshot.previousNetworkConnected
                ConditionType.WORKER_OFFLINE.name ->
                    snapshot.offlineWorkers >= (rule.conditionValue.toIntOrNull() ?: 1)
                else -> false
            }
            if (conditionMet) {
                val action = ActionType.entries.firstOrNull { it.name == rule.actionType }
                    ?: return@forEach
                actions += TriggeredAction(
                    ruleId = rule.id,
                    ruleName = rule.name,
                    action = action,
                    value = rule.actionValue,
                    detail = "${rule.name}: ${rule.conditionType} (${rule.conditionValue}) matched"
                )
            }
        }
        return actions
    }

    fun fromEntity(rule: com.universalbrain.ai.core.database.AutomationRule): EvalRule =
        EvalRule(rule.id, rule.name, rule.conditionType, rule.conditionValue, rule.actionType, rule.actionValue, rule.enabled)
}
