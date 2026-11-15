package com.multispace.manager.apk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.multispace.manager.MultiSpaceApp
import com.multispace.manager.R
import com.multispace.manager.service.BackgroundManagerService
import java.io.File

/**
 * One user-visible install status update, stored app-wide so the UI can show
 * exactly what the platform installer reported (no silent failures).
 */
data class InstallEvent(
    val kind: Kind,
    val message: String,
    val packageName: String? = null,
    val appId: String? = null
) {
    enum class Kind { STARTED, PENDING_USER_ACTION, SUCCESS, FAILURE }
}

/** The device-level "Install unknown apps" grant for MultiSpace is missing. */
class InstallPermissionNeededException :
    Exception("MultiSpace does not have 'Install unknown apps' permission on this device.")

/**
 * Installs an imported APK through the platform PackageInstaller (session API
 * with the mandatory user confirmation). Honest, supported path to run an
 * imported app — no security bypasses.
 */
object ApkInstaller {

    const val ACTION_INSTALL_STATUS = "com.multispace.manager.action.INSTALL_STATUS"
    const val EXTRA_SESSION_ID = "extra.session_id"
    const val EXTRA_APP_LABEL = "extra.app_label"
    const val EXTRA_PACKAGE_NAME = "extra.package_name"
    const val EXTRA_APP_ID = "extra.app_id"
    const val NOTIFICATION_ID_BASE = 2000

    fun install(
        context: Context,
        apkFile: File,
        appLabel: String,
        packageName: String?
    ): Result<Unit> = install(context, listOf(apkFile), appLabel, packageName, null)

    /**
     * Installs one or more APKs through a single platform PackageInstaller
     * session. Passing base + split APKs together is the supported way to
     * install XAPK/APKS bundles — no repackaging or signature changes.
     */
    fun install(
        context: Context,
        apkFiles: List<File>,
        appLabel: String,
        packageName: String?,
        appId: String?
    ): Result<Unit> {
        if (apkFiles.isEmpty() || apkFiles.any { !it.exists() }) {
            return Result.failure(IllegalArgumentException("APK file(s) missing for install."))
        }
        // Platform gate: without this grant the system confirmation dialog
        // never appears, so fail fast with an actionable reason.
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Result.failure(InstallPermissionNeededException())
        }
        return try {
            val installer: PackageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            apkFiles.forEach { apkFile ->
                apkFile.inputStream().use { input ->
                    session.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
                        input.copyTo(out, DEFAULT_BUFFER_SIZE)
                        session.fsync(out)
                    }
                }
            }

            val statusIntent = Intent(context, InstallStatusReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_APP_LABEL, appLabel)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_APP_ID, appId)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val sender = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            session.commit(sender.intentSender)
            session.close()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Receives PackageInstaller results.
 *
 *  - PENDING_USER_ACTION: auto-opens the system confirmation; if the OEM
 *    blocks background activity starts (common on MIUI/HyperOS and similar),
 *    a "Continue install" notification carrying the confirmation intent is
 *    posted as fallback.
 *  - SUCCESS / FAILURE: surfaced in-app (InstallEvent) and as a notification,
 *    including the platform's own status message.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_INSTALL_STATUS) return
        val extras = intent.extras ?: return
        val sessionId = extras.getInt(PackageInstaller.EXTRA_SESSION_ID)
        val status = extras.getInt(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val label = extras.getString(ApkInstaller.EXTRA_APP_LABEL, "App")
        val pkg = extras.getString(ApkInstaller.EXTRA_PACKAGE_NAME)
        val appId = extras.getString(ApkInstaller.EXTRA_APP_ID)
        val statusMessage = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val container = MultiSpaceApp.instance?.container

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = extras.getParcelable(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    container?.installEvent?.value = InstallEvent(
                        InstallEvent.Kind.FAILURE,
                        "$label: install confirmation was not provided by the system (status $status).",
                        pkg,
                        appId
                    )
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                var autoOpened = false
                try {
                    context.startActivity(confirm)
                    autoOpened = true
                } catch (_: Exception) {
                    // OEM blocked the background activity start — notification fallback below.
                }
                container?.installEvent?.value = InstallEvent(
                    InstallEvent.Kind.PENDING_USER_ACTION,
                    if (autoOpened) {
                        "Confirm the installation of $label in the system dialog."
                    } else {
                        "The install confirmation did not open automatically. Tap the 'Continue install' notification (or open the session again)."
                    },
                    pkg,
                    appId
                )
                postNotification(
                    context, sessionId,
                    "Continue installing $label",
                    "Tap to open the Android install confirmation.",
                    confirm
                )
            }
            PackageInstaller.STATUS_SUCCESS -> {
                container?.installEvent?.value = InstallEvent(
                    InstallEvent.Kind.SUCCESS,
                    "$label installed on device",
                    pkg,
                    appId
                )
                postNotification(context, sessionId, "MultiSpace install", "$label installed on device", null)
            }
            else -> {
                val message = buildString {
                    append("$label install failed")
                    statusMessage?.let { append(": $it") }
                    append(" (status $status)")
                }
                container?.installEvent?.value = InstallEvent(InstallEvent.Kind.FAILURE, message, pkg, appId)
                postNotification(context, sessionId, "MultiSpace install", message, null)
            }
        }
    }

    private fun postNotification(
        context: Context,
        id: Int,
        title: String,
        text: String,
        activityIntent: Intent?
    ) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }
        val builder = NotificationCompat.Builder(context, BackgroundManagerService.ensureChannel(context))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        activityIntent?.let {
            val pi = PendingIntent.getActivity(
                context, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pi)
        }
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ApkInstaller.NOTIFICATION_ID_BASE + id, builder.build())
        }
    }
}
