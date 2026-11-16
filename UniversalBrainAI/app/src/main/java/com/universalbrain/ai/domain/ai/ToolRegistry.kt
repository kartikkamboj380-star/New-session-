package com.universalbrain.ai.domain.ai

/**
 * Registry of internal tools the AI Brain may call, each with an explicit
 * permission level. The chat UI renders this registry so the user can see
 * exactly what the agent is allowed to do.
 */
object ToolRegistry {

    enum class Permission { READ, EXECUTE, WRITE }

    data class ToolDef(
        val name: String,
        val description: String,
        val permission: Permission
    )

    val tools: List<ToolDef> = listOf(
        ToolDef("query_accounts", "Read managed accounts and their statuses", Permission.READ),
        ToolDef("query_instances", "Read clone instances and their statuses", Permission.READ),
        ToolDef("query_apps", "Read imported apps and scanner status", Permission.READ),
        ToolDef("query_rules", "Read automation rules", Permission.READ),
        ToolDef("query_mining", "Read mining workers and their latest pool stats", Permission.READ),
        ToolDef("query_earnings", "Read tracked earnings records", Permission.READ),
        ToolDef("query_device", "Sample live device vitals (CPU/RAM/battery/network)", Permission.READ),
        ToolDef("generate_report", "Compose a full performance report from live data", Permission.READ),
        ToolDef("recommend", "Scan all modules and build prioritized suggestions", Permission.READ),
        ToolDef("analyze_optimization", "Analyze device vitals and recommend optimal settings", Permission.READ),
        ToolDef("apply_optimization", "Apply optimal polling settings to the mining engine", Permission.WRITE),
        ToolDef("create_instance", "Create a new clone instance (resource-capped)", Permission.WRITE),
        ToolDef("delete_instance", "Delete a clone instance and wipe its data", Permission.WRITE),
        ToolDef("set_instance_url", "Configure WEB instance URL and fresh-start behavior", Permission.WRITE),
        ToolDef("import_apps", "Scan installed apps and import them", Permission.WRITE),
        ToolDef("add_account", "Add an account with secrets into secure storage", Permission.WRITE),
        ToolDef("assign_account", "Assign an account to an instance", Permission.WRITE),
        ToolDef("add_worker", "Register a mining worker (pool API + wallet)", Permission.WRITE),
        ToolDef("configure_automation", "Create or update automation rules", Permission.WRITE),
        ToolDef("control_battery_saver", "Toggle the low-power performance profile", Permission.WRITE),
        ToolDef("wipe_secrets", "Erase all stored secrets (Keystore-backed store)", Permission.WRITE),
        ToolDef("launch_instances", "Launch clone instances on the device", Permission.EXECUTE),
        ToolDef("open_app", "Launch an installed app by name", Permission.EXECUTE),
        ToolDef("control_mining", "Pause or resume mining polling", Permission.EXECUTE),
        ToolDef("ads_status", "Read ads consent, points, cap and streak", Permission.READ),
        ToolDef("ads_watch", "Check rewarded-ads readiness and queue a session", Permission.EXECUTE),
        ToolDef("control_engine", "Start or stop the background engine service", Permission.EXECUTE),
        ToolDef("help", "Explain what the agent can do", Permission.READ)
    )

    fun byName(name: String): ToolDef? = tools.firstOrNull { it.name == name }

    fun permissionOf(name: String): Permission? = byName(name)?.permission
}
