package com.aistra.hail.app

import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManager {
    val lockScreen: Boolean
        get() = with(HailData.workingMode) {
            when {
                startsWith(HailData.OWNER) -> HPolicy.lockScreen
                startsWith(HailData.DHIZUKU) -> HDhizuku.lockScreen
                startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.lockScreen
                startsWith(HailData.SHIZUKU) -> HShizuku.lockScreen
                else -> false
            }
        }

    /**
     * Whether apps of other profiles can be frozen and launched.
     *
     * Only the shell/root based working modes reach another user consistently. The apps can
     * still be listed through `LauncherApps` in every mode, see [HUsers.canListOtherProfiles].
     */
    val crossProfileSupported: Boolean get() = HUsers.canManageOtherProfiles

    /**
     * Collapse unsupported users back to the current one, so a caller can never operate on a
     * profile the current working mode cannot handle.
     */
    fun effectiveUserId(userId: Int): Int =
        if (crossProfileSupported || userId == HPackages.myUserId) userId else HPackages.myUserId

    fun isAppFrozen(packageName: String): Boolean = isAppFrozen(HPackages.myUserId, packageName)

    fun isAppFrozen(userId: Int, packageName: String): Boolean = with(HailData.workingMode) {
        val uid = effectiveUserId(userId)
        when {
            endsWith(HailData.STOP) -> HPackages.isAppStopped(packageName, uid)
            endsWith(HailData.DISABLE) -> HPackages.isAppDisabled(packageName, uid)
            endsWith(HailData.HIDE) -> HPackages.isAppHidden(packageName, uid)
            endsWith(HailData.SUSPEND) -> HPackages.isAppSuspended(packageName, uid)
            else -> HPackages.isAppDisabled(packageName, uid)
                    || HPackages.isAppHidden(packageName, uid)
                    || HPackages.isAppSuspended(packageName, uid)
        }
    }

    fun setListFrozen(frozen: Boolean, vararg appInfo: AppInfo): String? {
        val excludeMe = appInfo.filter { it.packageName != BuildConfig.APPLICATION_ID }
        var i = 0
        var denied = false
        var name = String()
        when (HailData.workingMode) {
            // call setListFrozen for some batch-style working mode here
            // fallback to setAppFrozen otherwise
            else -> {
                excludeMe.forEach {
                    when {
                        setAppFrozen(it.userId, it.packageName, frozen) -> {
                            i++
                            name = it.name.toString()
                        }

                        it.applicationInfo != null -> denied = true
                    }
                }
            }
        }
        return if (denied && i == 0) null else if (i == 1) name else i.toString()
    }

    fun setAppFrozen(packageName: String, frozen: Boolean): Boolean =
        setAppFrozen(HPackages.myUserId, packageName, frozen)

    fun setAppFrozen(userId: Int, packageName: String, frozen: Boolean): Boolean {
        if (packageName == BuildConfig.APPLICATION_ID) return false
        val uid = effectiveUserId(userId)
        // The cached query of another profile is stale now, both the one taken before the state
        // changed and the one taken here, while the shell command runs
        if (uid != HPackages.myUserId) HPackages.invalidateCache()
        val result = when (HailData.workingMode) {
            HailData.MODE_OWNER_HIDE -> HPolicy.setAppHidden(packageName, frozen)
            HailData.MODE_OWNER_SUSPEND -> HPolicy.setAppSuspended(packageName, frozen)
            HailData.MODE_DHIZUKU_HIDE -> HDhizuku.setAppHidden(packageName, frozen)
            HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_SU_STOP -> !frozen || HShell.forceStopApp(packageName, uid)
            HailData.MODE_SU_DISABLE -> HShell.setAppDisabled(packageName, frozen, uid)
            HailData.MODE_SU_HIDE -> HShell.setAppHidden(packageName, frozen, uid)
            HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, frozen, uid)
            HailData.MODE_SHIZUKU_STOP -> !frozen || HShizuku.forceStopApp(packageName, uid)
            HailData.MODE_SHIZUKU_DISABLE -> HShizuku.setAppDisabled(packageName, frozen, uid)
            HailData.MODE_SHIZUKU_HIDE -> HShizuku.setAppHidden(packageName, frozen, uid)
            HailData.MODE_SHIZUKU_SUSPEND -> HShizuku.setAppSuspended(packageName, frozen, uid)
            HailData.MODE_ISLAND_HIDE -> HIsland.setAppHidden(packageName, frozen)
            HailData.MODE_ISLAND_SUSPEND -> HIsland.setAppSuspended(packageName, frozen)
            HailData.MODE_PRIVAPP_STOP -> !frozen || HPackages.forceStopApp(packageName, uid)
            HailData.MODE_JAILBREAK_DISABLE, HailData.MODE_PRIVAPP_DISABLE ->
                HPackages.setAppDisabled(packageName, frozen, uid)

            else -> false
        }
        // The state read back by the freeze itself, or one taken while the command was running,
        // may still describe the app before the change
        if (uid != HPackages.myUserId) HPackages.invalidateCache()
        return result
    }

    /**
     * Launch an app, optionally in another profile.
     *
     * Returns false when the launch intent cannot be resolved, e.g. the app is not installed
     * in that profile.
     */
    fun launchApp(userId: Int, packageName: String): Boolean {
        val uid = effectiveUserId(userId)
        val intent = if (uid == HPackages.myUserId) {
            HPackages.getLaunchIntentForPackage(packageName, uid)
        } else HShizuku.getLaunchIntentForPackage(packageName, uid)
        return when {
            intent == null -> false
            uid == HPackages.myUserId -> {
                app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }

            else -> HShizuku.startActivityAsUser(intent, uid)
        }
    }

    fun uninstallApp(packageName: String): Boolean = with(HailData.workingMode) {
        if (
            (startsWith(HailData.OWNER) && HPolicy.uninstallApp(packageName))
            || (startsWith(HailData.DHIZUKU) && HDhizuku.uninstallApp(packageName))
            || ((startsWith(HailData.SU) || startsWith(HailData.JAILBREAK)) && HShell.uninstallApp(packageName))
            || (startsWith(HailData.SHIZUKU) && HShizuku.uninstallApp(packageName))
        )
            return true
        HUI.startActivity(Intent.ACTION_DELETE, HPackages.packageUri(packageName))
        false
    }

    fun reinstallApp(packageName: String): Boolean = with(HailData.workingMode) {
        when {
            startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.reinstallApp(packageName)
            startsWith(HailData.SHIZUKU) -> HShizuku.reinstallApp(packageName)
            else -> false
        }
    }

    suspend fun execute(command: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        with(HailData.workingMode) {
            when {
                startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.execute(command, true)
                startsWith(HailData.SHIZUKU) -> HShizuku.execute(command)
                else -> 0 to null
            }
        }
    }
}