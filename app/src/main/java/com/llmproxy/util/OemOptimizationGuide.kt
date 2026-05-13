package com.llmproxy.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Provides defensive OEM-specific intents and guidance steps for battery optimization exclusions.
 *
 * We try known vendor settings components first, then gracefully fall back to generic Android
 * battery optimization/app-details settings so unknown OEMs never crash this flow.
 */
object OemOptimizationGuide {

    fun manufacturerDisplayName(rawManufacturer: String = Build.MANUFACTURER): String =
        rawManufacturer
            .trim()
            .ifBlank { "Unknown" }
            .split(Regex("\\s+"))
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.titlecase() }
            }

    fun instructions(rawManufacturer: String = Build.MANUFACTURER): List<String> {
        return when (normalizeManufacturer(rawManufacturer)) {
            "xiaomi", "redmi", "poco" -> listOf(
                "Open Security/Permissions settings for this app.",
                "Enable Auto-start for LLM Proxy.",
                "Set Battery saver to No restrictions/No optimization.",
            )
            "samsung" -> listOf(
                "Open Battery settings for this app.",
                "Set battery usage to Unrestricted.",
                "Exclude the app from sleeping/deep sleeping app lists.",
            )
            "huawei", "honor" -> listOf(
                "Open App launch / Startup manager.",
                "Allow auto-launch, secondary launch, and run in background.",
                "Disable battery optimization for LLM Proxy.",
            )
            "oppo", "realme", "oneplus" -> listOf(
                "Open Auto-start management for this app.",
                "Allow background running and startup.",
                "Disable battery optimization for LLM Proxy.",
            )
            "vivo", "iqoo" -> listOf(
                "Open Background startup manager.",
                "Allow auto start and background activity.",
                "Disable battery optimization for LLM Proxy.",
            )
            else -> listOf(
                "Open battery optimization settings.",
                "Set LLM Proxy to unrestricted/not optimized.",
                "Allow background activity for stable server operation.",
            )
        }
    }

    fun resolveSettingsIntent(context: Context, rawManufacturer: String = Build.MANUFACTURER): Intent {
        val packageManager = context.packageManager
        val intents = manufacturerSpecificIntents(context, rawManufacturer) + genericFallbackIntents(context)
        return intents.firstOrNull { intent ->
            runCatching { intent.resolveActivity(packageManager) != null }.getOrDefault(false)
        } ?: Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun manufacturerSpecificIntents(context: Context, rawManufacturer: String): List<Intent> {
        return when (normalizeManufacturer(rawManufacturer)) {
            "xiaomi", "redmi", "poco" -> listOf(
                componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Intent("miui.intent.action.OP_AUTO_START"),
            )
            "samsung" -> listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            "huawei", "honor" -> listOf(
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            "oppo", "realme", "oneplus" -> listOf(
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            "vivo", "iqoo" -> listOf(
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            else -> emptyList()
        }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun genericFallbackIntents(context: Context): List<Intent> {
        return listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
            Intent(Settings.ACTION_SETTINGS),
        ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    private fun normalizeManufacturer(rawManufacturer: String): String =
        rawManufacturer.trim().lowercase()
}
