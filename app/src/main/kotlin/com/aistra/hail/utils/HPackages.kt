package com.aistra.hail.utils

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.aistra.hail.HailApp.Companion.app
import org.lsposed.hiddenapibypass.HiddenApiBypass

object HPackages {
    val myUserId get() = android.os.Process.myUserHandle().hashCode()

    fun packageUri(packageName: String) = "package:$packageName"

    /** Launch intent of an app. Always resolve it in the profile the app belongs to. */
    fun getLaunchIntentForPackage(packageName: String, userId: Int = myUserId): Intent? =
        if (userId == myUserId) app.packageManager.getLaunchIntentForPackage(packageName)
        else HShizuku.getLaunchIntentForPackage(packageName, userId)

    /**
     * Apps of a profile, including uninstalled ones so frozen apps stay visible.
     *
     * The current profile goes through `PackageManager`, any other profile is read from
     * `LauncherApps`, which resolves the launcher entries inside that user.
     */
    fun getInstalledApplicationsAsUser(userId: Int): List<ApplicationInfo> =
        if (userId == myUserId) getInstalledApplications() else HShizuku.getLauncherActivities(userId)

    @RequiresApi(Build.VERSION_CODES.N)
    fun packageUid(packageName: String, userId: Int = myUserId) = if (HTarget.T) app.packageManager.getPackageUid(
        packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
    ) else app.packageManager.getPackageUid(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)

    fun getInstalledApplications(flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192): List<ApplicationInfo> =
        if (HTarget.T) app.packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getInstalledApplications(flags)

    fun getUnhiddenPackageInfoOrNull(
        packageName: String, flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192
    ) = runCatching {
        if (HTarget.T) app.packageManager.getPackageInfo(
            packageName, PackageManager.PackageInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getPackageInfo(packageName, flags)
    }.getOrNull()

    /**
     * Application info of a profile.
     *
     * Each query of another profile is a binder round trip, which is far too expensive for the
     * main thread. The result is therefore cached for a short while and dropped as soon as
     * [invalidateCache] is called (after a freeze, a refresh, a profile change...).
     *
     * Keyed by the profile and the package name itself; a hash of the package name could
     * collide across packages and answer with the info of a different app.
     */
    private val otherUserCache = HashMap<Pair<Int, String>, Pair<Long, ApplicationInfo>>()
    private val otherUserCacheTtl = 10_000L

    fun invalidateCache() {
        synchronized(otherUserCache) { otherUserCache.clear() }
        HShizuku.invalidateDumpCache()
    }

    private fun cachedApplicationInfoAsUser(userId: Int, packageName: String): ApplicationInfo {
        val key = userId to packageName
        synchronized(otherUserCache) {
            otherUserCache[key]?.takeIf { SystemClock.elapsedRealtime() - it.first < otherUserCacheTtl }
                ?.let { return it.second }
        }
        // Query outside the lock, callers run on several threads
        val lock = SystemClock.elapsedRealtime()
        val info = HShizuku.getApplicationInfoAsUser(packageName, userId)
            ?: queryDisabledApplicationInfo(packageName, userId)
            ?: throw NameNotFoundException(packageName)
        synchronized(otherUserCache) { otherUserCache[key] = lock to info }
        return info
    }

    /**
     * Application info of an app that `LauncherApps` no longer reports, i.e. a frozen one.
     *
     * `LauncherApps` resolves the launcher entries inside a user, so a disabled or hidden app
     * disappears from it even though it is still installed. The shell sees every package, the
     * output of `cmd package dump` is parsed for the installed state and the apk path.
     */
    private fun queryDisabledApplicationInfo(packageName: String, userId: Int): ApplicationInfo? =
        HShizuku.dumpPackageAsUser(packageName, userId)?.let { dump ->
            // `dump` only knows the app when its package block is there, a missing user line
            // means the app is not installed in that profile
            val userLine = Regex("""^\s*User $userId: .*$""", RegexOption.MULTILINE).find(dump)
                ?: return@let null
            val installed = Regex("""\binstalled=(\w+)""").find(userLine.value)
                ?.groupValues?.get(1)?.toBoolean() ?: true
            // enabled=1 is the default state, anything else counts as disabled for an app that
            // LauncherApps already dropped
            val enabled = Regex("""\benabled=(\d)""").find(userLine.value)
                ?.groupValues?.get(1) == "1"
            val codePath = Regex("""^\s*codePath=(\S+)""", RegexOption.MULTILINE)
                .find(dump)?.groupValues?.get(1)
            ApplicationInfo().apply {
                this.packageName = packageName
                flags = if (installed) ApplicationInfo.FLAG_INSTALLED else 0
                this.enabled = enabled
                // loadLabel and the icon loader need a real apk, the one shared by all profiles
                sourceDir = codePath
            }
        }

    fun getApplicationInfoOrNull(
        packageName: String,
        userId: Int = myUserId,
        flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192
    ) = runCatching {
        // The current user goes through the cached PackageManager, other users through LauncherApps
        if (userId == myUserId) {
            if (HTarget.T) app.packageManager.getApplicationInfo(
                packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong())
            )
            else app.packageManager.getApplicationInfo(packageName, flags)
        } else cachedApplicationInfoAsUser(userId, packageName)
    }.getOrNull()

    fun isAppDisabled(packageName: String, userId: Int = myUserId): Boolean {
        if (userId == myUserId) return getApplicationInfoOrNull(packageName)?.enabled?.not() ?: false
        // LauncherApps reports an enabled app only, a frozen one is missing from it altogether
        return getApplicationInfoOrNull(packageName, userId)?.let { !it.enabled } ?: true
    }

    fun isAppHidden(packageName: String, userId: Int = myUserId): Boolean = getApplicationInfoOrNull(packageName, userId)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 1 == 1
    } ?: false

    fun isAppStopped(packageName: String, userId: Int = myUserId): Boolean =
        getApplicationInfoOrNull(packageName, userId)?.run { flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED }
            ?: false

    fun isAppSuspended(packageName: String, userId: Int = myUserId): Boolean = getApplicationInfoOrNull(packageName, userId)?.let {
        when {
//            This method will cause NameNotFoundException with uninstalled packages
//            HTarget.Q -> app.packageManager.isPackageSuspended(packageName)
            HTarget.N -> it.flags and ApplicationInfo.FLAG_SUSPENDED == ApplicationInfo.FLAG_SUSPENDED
            else -> false
        }
    } ?: false

    fun isAppUninstalled(packageName: String, userId: Int = myUserId): Boolean =
        getApplicationInfoOrNull(packageName, userId)
            ?.run { flags and ApplicationInfo.FLAG_INSTALLED != ApplicationInfo.FLAG_INSTALLED }
            ?: true

    fun isPrivilegedApp(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 8 == 8
    } ?: false

    fun canUninstallNormally(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.sourceDir?.startsWith("/data") ?: false

    fun forceStopApp(packageName: String, userId: Int = myUserId): Boolean = runCatching {
        // Other profiles need the privileged ActivityManager implementation
        if (userId != myUserId) return HShizuku.forceStopApp(packageName, userId)
        app.getSystemService<ActivityManager>()!!.let {
            if (HTarget.P) HiddenApiBypass.invoke(it::class.java, it, "forceStopPackage", packageName)
            else it::class.java.getMethod("forceStopPackage", String::class.java).invoke(it, packageName)
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    fun setAppDisabled(packageName: String, disabled: Boolean, userId: Int = myUserId): Boolean {
        if (userId != myUserId) return HShizuku.setAppDisabled(packageName, disabled, userId)
        getApplicationInfoOrNull(packageName) ?: return false
        if (disabled) forceStopApp(packageName)
        runCatching {
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            app.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }.onFailure {
            HLog.e(it)
        }
        return isAppDisabled(packageName) == disabled
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = runCatching {
        app.getSystemService<AppOpsManager>()!!.let {
            HiddenApiBypass.invoke(
                it::class.java,
                it,
                "setMode",
                "android:run_any_in_background",
                packageUid(packageName),
                packageName,
                if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
            )
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }
}