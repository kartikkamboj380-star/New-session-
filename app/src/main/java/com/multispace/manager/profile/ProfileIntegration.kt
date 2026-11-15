package com.multispace.manager.profile

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle

/**
 * Genuine, platform-supported isolation for installed apps.
 *
 * Android's sandbox does not allow one app to run another app's APK inside
 * itself with a separate identity. What the platform *does* support:
 *
 *  - Managed work profiles (created via device provisioning — a regular app
 *    cannot create one, but it can detect and launch apps inside one).
 *  - OEM clone profiles (Android 14 "clone user" or vendor dual-app features).
 *
 * This class detects the profiles that actually exist on the device and, when
 * an imported app is installed in one of them, launches it there — giving a
 * genuinely isolated second instance. When no profile exists, it reports that
 * honestly instead of pretending isolation happened.
 */
class ProfileIntegration(private val context: Context) {

    data class ProfileTarget(
        val handle: UserHandle,
        val label: String,
        val isMain: Boolean
    )

    private fun launcherApps(): LauncherApps? =
        context.getSystemService(LauncherApps::class.java)

    fun availableProfiles(): List<ProfileTarget> {
        val la = launcherApps() ?: return emptyList()
        val main = Process.myUserHandle()
        return la.profiles.mapIndexed { index, handle ->
            val isMain = handle == main
            ProfileTarget(
                handle = handle,
                label = if (isMain) {
                    "Main profile"
                } else {
                    // UserHandle type info is not public; work and clone
                    // profiles both surface the same way through LauncherApps.
                    "Isolated profile ${index + 1} (work/clone)"
                },
                isMain = isMain
            )
        }
    }

    /** @return true when the package has a launcher activity in [handle]. */
    fun isInstalledIn(handle: UserHandle, packageName: String): Boolean {
        val la = launcherApps() ?: return false
        return try {
            la.getActivityList(packageName, handle).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Launches the package inside [handle]. Returns an error message on
     * failure instead of silently falling back to the main profile.
     */
    fun launchIn(handle: UserHandle, packageName: String): String? {
        val la = launcherApps() ?: return "Profile launcher unavailable on this device."
        return try {
            val activities = la.getActivityList(packageName, handle)
            val first = activities.firstOrNull()
                ?: return "App is not installed in this profile. Install it there first."
            la.startMainActivity(first.componentName, handle, null, null)
            null
        } catch (e: Exception) {
            "Launch in profile failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Capability description shown in the UI so users understand exactly what
     * isolation is possible on *their* device.
     */
    fun describeCapability(): String {
        val profiles = availableProfiles()
        val isolated = profiles.filterNot { it.isMain }
        return if (isolated.isEmpty()) {
            "No managed/clone profile on this device. Multi-instance isolation for installed apps needs a work profile (device provisioning) or an OEM clone profile. MultiSpace detects and uses them when present."
        } else {
            "Isolated profiles detected: ${isolated.joinToString { it.label }}. Apps installed inside these profiles run as fully separate instances."
        }
    }
}
