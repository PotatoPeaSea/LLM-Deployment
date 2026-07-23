package com.geniechatrn.genie

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The tools that need no permission and no network: pure reads of state the app
 * already has access to.
 *
 * These exist mostly because they are the ones a local assistant is actually
 * asked for ("what time is it", "how's my battery"), and answering them from
 * the device is both instant and correct -- where the model's own guess would
 * be neither, since its weights have no idea what today is.
 */

object DateTimeTool : Tool {
    override val name = "get_datetime"

    override fun schema() = functionSchema(
        name,
        "Get the current date, time and timezone on this device. Use this for " +
            "any question about the current date or time, or to compute how far " +
            "away something is.",
        noArgs(),
    )

    override fun run(context: Context, args: JSONObject): String {
        val now = Date()
        val stamp = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm:ss", Locale.getDefault()).format(now)
        val zone = java.util.TimeZone.getDefault()
        return "$stamp (${zone.id}, ${zone.getDisplayName(false, java.util.TimeZone.SHORT)})"
    }
}

object BatteryTool : Tool {
    override val name = "get_battery"

    override fun schema() = functionSchema(
        name,
        "Get this device's battery level, whether it is charging, and its " +
            "temperature.",
        noArgs(),
    )

    override fun run(context: Context, args: JSONObject): String {
        // The sticky broadcast carries the full picture in one shot;
        // BatteryManager alone would not give plugged state or temperature.
        val status: Intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return "Battery status unavailable."

        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "charging (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "charging (USB)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging (wireless)"
            else -> "on battery"
        }
        val tenthsC = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val temp = if (tenthsC > 0) ", ${tenthsC / 10.0}°C" else ""
        return "Battery ${if (percent >= 0) "$percent%" else "unknown"}, $charging$temp."
    }
}

object DeviceInfoTool : Tool {
    override val name = "get_device_info"

    override fun schema() = functionSchema(
        name,
        "Get information about this device: model, manufacturer, Android " +
            "version, and free storage and memory.",
        noArgs(),
    )

    override fun run(context: Context, args: JSONObject): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_000_000_000.0
        val totalGb = stat.totalBytes / 1_000_000_000.0

        val mem = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(mem)

        return buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), ")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ")
            append("SoC ${socModel()}. ")
            append(String.format(Locale.US, "Storage %.1f GB free of %.1f GB. ", freeGb, totalGb))
            append(String.format(Locale.US, "RAM %.1f GB free of %.1f GB.",
                mem.availMem / 1e9, mem.totalMem / 1e9))
        }
    }

    /** Build.SOC_MODEL is API 31+; this app's minSdk is 27. */
    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "unknown"
}
