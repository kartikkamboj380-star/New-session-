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
        ToolDef("create_instance", "Create a new clone instance (resource-capped)", Permission.WRITE),
        ToolDef("launch_instances", "Launch clone instances on the device", Permission.EXECUTE),
        ToolDef("query_mining", "Read mining workers and their latest pool stats", Permission.READ),
        ToolDef("query_earnings", "Read tracked earnings records", Permission.READ),
        ToolDef("analyze_optimization", "Analyze device vitals and recommend optimal settings", Permission.READ),
        ToolDef("configure_automation", "Create or update automation rules", Permission.WRITE),
        ToolDef("query_device", "Sample live device vitals (CPU/RAM/battery/network)", Permission.READ),
        ToolDef("generate_report", "Compose a full performance report from live data", Permission.READ),
        ToolDef("control_engine", "Start or stop the background engine service", Permission.EXECUTE),
        ToolDef("help", "Explain what the agent can do", Permission.READ)
    )

    fun byName(name: String): ToolDef? = tools.firstOrNull { it.name == name }

    fun permissionOf(name: String): Permission? = byName(name)?.permission
}
