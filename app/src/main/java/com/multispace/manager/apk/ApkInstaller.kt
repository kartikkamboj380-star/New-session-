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
 * Installs an imported APK on the device through the platform PackageInstaller
 * (session API with the mandatory user confirmation). This is the honest,
 * supported path to run an imported app: MultiSpace never hides, fakes or
 * bypasses Android's install flow.
 */
object ApkInstaller {

    const val ACTION_INSTALL_STATUS = "com.multispace.manager.action.INSTALL_STATUS"
    const val EXTRA_SESSION_ID = "extra.session_id"
    const val EXTRA_APP_LABEL = "extra.app_label"
    const val NOTIFICATION_ID_BASE = 2000

    fun install(context: Context, apkFile: File, appLabel: String): Result<Unit> {
        return try {
            val installer: PackageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            apkFile.inputStream().use { input ->
                session.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    session.fsync(out)
                }
            }

            val statusIntent = Intent(context, InstallStatusReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_APP_LABEL, appLabel)

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
 * Receives PackageInstaller results. When the platform asks for the user's
 * install confirmation it launches the confirmation activity; otherwise it
 * posts a status notification.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_INSTALL_STATUS) return
        val extras = intent.extras ?: return
        val sessionId = extras.getInt(PackageInstaller.EXTRA_SESSION_ID)
        val status = extras.getInt(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val label = extras.getString(ApkInstaller.EXTRA_APP_LABEL, "App")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent: Intent? = extras.getParcelable(Intent.EXTRA_INTENT)
            confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                runCatching { context.startActivity(it) }
            }
            return
        }

        val message = if (status == PackageInstaller.STATUS_SUCCESS) {
            "$label installed on device"
        } else {
            "$label install failed (status $status)"
        }
        MultiSpaceApp.instance?.container?.installEvents?.tryEmit(message)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }

        val channel = BackgroundManagerService.ensureChannel(context)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MultiSpace install")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ApkInstaller.NOTIFICATION_ID_BASE + sessionId, notification)
        }
    }
}
