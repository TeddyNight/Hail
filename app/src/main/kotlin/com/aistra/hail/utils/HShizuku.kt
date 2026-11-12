package com.aistra.hail.utils

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.content.pm.LauncherApps
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.aistra.hail.BuildConfig
import com.aistra.hail.app.HailData
import com.aistra.hail.HailApp.Companion.app
import moe.shizuku.server.IShizukuService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object HShizuku {
    /** Whether Shizuku runs as root, a binder that is not received yet simply means no. */
    val isRoot get() = runCatching { Shizuku.getUid() == 0 }.getOrDefault(false)
    private val callerPackage get() = if (isRoot) BuildConfig.APPLICATION_ID else "com.android.shell"

    private fun asInterface(className: String, original: IBinder): Any = Class.forName("$className\$Stub").run {
        if (HTarget.P) HiddenApiBypass.invoke(this, null, "asInterface", ShizukuBinderWrapper(original))
        else getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(original))
    }

    private fun asInterface(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    val lockScreen
        get() = runCatching {
            val input = asInterface("android.hardware.input.IInputManager", Context.INPUT_SERVICE)
            val inject = input::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.java
            )
            val now = SystemClock.uptimeMillis()
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0), 0
            )
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER, 0), 0
            )
            true
        }.getOrElse {
            HLog.e(it)
            false
        }

    fun forceStopApp(packageName: String, userId: Int = HPackages.myUserId): Boolean = runCatching {
        asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE).let {
            if (HTarget.P) HiddenApiBypass.invoke(
                it::class.java, it, "forceStopPackage", packageName, userId
            ) else it::class.java.getMethod(
                "forceStopPackage", String::class.java, Int::class.java
            ).invoke(
                it, packageName, userId
            )
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    fun setAppDisabled(packageName: String, disabled: Boolean, userId: Int = HPackages.myUserId): Boolean {
        HPackages.getApplicationInfoOrNull(packageName, userId) ?: return false
        if (disabled) forceStopApp(packageName, userId)
        runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                isRoot -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            pm::class.java.getMethod(
                "setApplicationEnabledSetting",
                String::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                String::class.java
            ).invoke(pm, packageName, newState, 0, userId, BuildConfig.APPLICATION_ID)
        }.onFailure {
            HLog.e(it)
        }
        return HPackages.isAppDisabled(packageName, userId) == disabled
    }

    fun setAppHidden(packageName: String, hidden: Boolean, userId: Int = HPackages.myUserId): Boolean {
        HPackages.getApplicationInfoOrNull(packageName, userId) ?: return false
        if (hidden) forceStopApp(packageName, userId)
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            pm::class.java.getMethod(
                "setApplicationHiddenSettingAsUser", String::class.java, Boolean::class.java, Int::class.java
            ).invoke(pm, packageName, hidden, userId) as Boolean
        }.getOrElse {
            HLog.e(it)
            false
        }
    }

    fun setAppSuspended(packageName: String, suspended: Boolean, userId: Int = HPackages.myUserId): Boolean {
        HPackages.getApplicationInfoOrNull(packageName, userId) ?: return false
        if (HTarget.P) setAppRestricted(packageName, suspended, userId)
        if (suspended) forceStopApp(packageName, userId)
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            (when {
                HTarget.U -> runCatching {
                    HiddenApiBypass.invoke(
                        pm::class.java,
                        pm,
                        "setPackagesSuspendedAsUser",
                        arrayOf(packageName),
                        suspended,
                        null,
                        null,
                        if (suspended) suspendDialogInfo else null,
                        0,
                        callerPackage,
                        HPackages.myUserId /*suspendingUserId*/,
                        userId /*targetUserId*/
                    )
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceQ(pm, packageName, suspended, userId)
                    else throw it
                }

                HTarget.Q -> runCatching {
                    setPackagesSuspendedAsUserSinceQ(pm, packageName, suspended, userId)
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceP(pm, packageName, suspended, userId)
                    else throw it
                }

                HTarget.P -> setPackagesSuspendedAsUserSinceP(pm, packageName, suspended, userId)

                HTarget.N -> pm::class.java.getMethod(
                    "setPackagesSuspendedAsUser", Array<String>::class.java, Boolean::class.java, Int::class.java
                ).invoke(pm, arrayOf(packageName), suspended, userId)

                else -> return false
            } as Array<*>).isEmpty()
        }.getOrElse {
            HLog.e(it)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setPackagesSuspendedAsUserSinceQ(
        pm: Any, packageName: String, suspended: Boolean, userId: Int = HPackages.myUserId
    ): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            if (suspended) suspendDialogInfo else null,
            callerPackage,
            userId
        )

    @RequiresApi(Build.VERSION_CODES.P)
    private fun setPackagesSuspendedAsUserSinceP(
        pm: Any, packageName: String, suspended: Boolean, userId: Int = HPackages.myUserId
    ): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            null /*dialogMessage*/,
            callerPackage,
            userId
        )

    private val suspendDialogInfo: Any
        @RequiresApi(Build.VERSION_CODES.Q) @SuppressLint("PrivateApi") get() = HiddenApiBypass.newInstance(
            Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        ).let {
            HiddenApiBypass.invoke(it::class.java, it, "setNeutralButtonAction", 1 /*BUTTON_ACTION_UNSUSPEND*/)
            HiddenApiBypass.invoke(it::class.java, it, "build")
        }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean, userId: Int = HPackages.myUserId): Boolean = runCatching {
        val appops = asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
        HiddenApiBypass.invoke(
            appops::class.java,
            appops,
            "setMode",
            HiddenApiBypass.invoke(AppOpsManager::class.java, null, "strOpToOp", "android:run_any_in_background"),
            HPackages.packageUid(packageName),
            packageName,
            if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
        )
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    /**
     * Apps of another profile, as seen by the launcher.
     *
     * `LauncherApps` resolves the launcher activities inside the given user, so it does not need
     * the `IPackageManager` interface (whose signatures differ between releases and do not expose
     * every method on the binder proxy). Only apps with a launcher entry are returned.
     */
    fun getLauncherActivities(userId: Int): List<ApplicationInfo> = runCatching {
        app.getSystemService(LauncherApps::class.java)
            .getActivityList(null, userHandleOf(userId))
            .map { it.applicationInfo }
    }.getOrElse {
        HLog.e(it)
        emptyList()
    }

    /** Profiles the launcher knows about, the public `LauncherApps.getProfiles` does the work. */
    fun getLauncherProfiles(): Set<Int> = runCatching {
        app.getSystemService(LauncherApps::class.java).profiles.map { it.hashCode() }.toSet()
    }.getOrElse {
        HLog.e(it)
        emptySet()
    }

    /**
     * Build a `UserHandle` for a user id.
     *
     * The constructor is hidden API and `HiddenApiBypass.newInstance` cannot match its `int`
     * parameter, so the hidden `of(int)` factory is invoked instead.
     */
    private fun userHandleOf(userId: Int): UserHandle =
        HiddenApiBypass.invoke(UserHandle::class.java, null, "of", userId as Any) as UserHandle

    /**
     * Application info of one app in another profile.
     *
     * The launcher entry is preferred because it carries the label and icon the launcher shows
     * inside that user, i.e. the name the ROM gave the clone ("微信 1", "微信 2"), while
     * `getApplicationInfo` answers with the plain apk label shared by every profile.
     *
     * A frozen app has no launcher entry, so `getApplicationInfo` is the fallback: it still
     * answers for any installed package and carries the enabled state.
     */
    fun getApplicationInfoAsUser(packageName: String, userId: Int): ApplicationInfo? {
        val launcher = app.getSystemService(LauncherApps::class.java)
        val activityInfo = launcher.getActivityList(packageName, userHandleOf(userId)).firstOrNull()
        val appInfo = runCatching {
            launcher.getApplicationInfo(packageName, 0, userHandleOf(userId))
        }.onFailure {
            HLog.e(it)
        }.getOrNull()
        return activityInfo?.applicationInfo ?: appInfo
    }

    /**
     * The `dumpsys package` blocks of one package, restricted to the given user.
     *
     * Used for apps that `LauncherApps` hides (frozen apps of another profile): the dump carries
     * the per user install state, the enabled setting and the apk path.
     */
    private val dumpCache = HashMap<Pair<String, Int>, Pair<Long, String?>>()
    private val dumpCacheTtl = 5_000L

    fun dumpPackageAsUser(packageName: String, userId: Int): String? = synchronized(dumpCache) {
        dumpCache[packageName to userId]?.takeIf {
            SystemClock.elapsedRealtime() - it.first < dumpCacheTtl
        }?.second
    } ?: runCatching {
        val (code, output) = shell("cmd package dump $packageName --user $userId")
        if (code != 0) null else output
    }.getOrNull().also {
        synchronized(dumpCache) { dumpCache[packageName to userId] = SystemClock.elapsedRealtime() to it }
    }

    /** Drop the cached package dumps, e.g. right after the enabled state of an app changed. */
    fun invalidateDumpCache() = synchronized(dumpCache) { dumpCache.clear() }

    /**
     * Run a command as root, or as the shell through Shizuku, matching the working mode.
     *
     * Root working modes go through su directly; Shizuku and PrivApp modes go through the
     * Shizuku service, falling back to su when it is not reachable.
     */
    private fun shell(command: String): Pair<Int, String?> {
        val root = when (HailData.workingMode) {
            HailData.MODE_SU_STOP, HailData.MODE_SU_DISABLE, HailData.MODE_SU_HIDE, HailData.MODE_SU_SUSPEND,
            HailData.MODE_PRIVAPP_STOP, HailData.MODE_PRIVAPP_DISABLE -> true

            else -> null
        }
        if (root != null) return HShell.execute(command, true)
        val result = execute(command)
        // An unreachable Shizuku may still have su available
        if (result.first != 0 && result.second.orEmpty().contains("binder haven't been received")) {
            HShell.execute(command, true).let { if (it.first == 0) return it }
        }
        return result
    }

    /**
     * Enumerate profiles.
     *
     * The signature of `IUserManager.getUsers` differs between Android versions and ROMs, so
     * every known variant is tried before falling back to `cmd user list -v`, which does not
     * depend on the interface at all. `UserInfo` is not exposed by the public SDK,
     * the fields are read reflectively.
     */
    fun getUsers(): List<HUsers.Profile> {
        // Most reliable first: the root/shell path does not need Shizuku at all. The working
        // mode may be Root while Shizuku is not running, and vice versa.
        runCatching { getUsersFromShell() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        // Android 14+ moved the AIDL interfaces to android.os, older releases keep them in android.app
        val service = arrayOf("android.os.IUserManager", "android.app.IUserManager").firstNotNullOfOrNull {
            runCatching { asInterface(it, "user") }.getOrNull()
        }
        val probes = listOf<(Any) -> List<*>?>(
            // The arity changed over releases, try the widest one first:
            // Android 15+: getUsers(boolean excludePartial, boolean excludeDying, boolean excludePreCreated)
            // Android 14:  getUsers(boolean excludeDying)
            { it.invokeList("getUsers", false, false, false) },
            { it.invokeList("getUsers", false, false) },
            { it.invokeList("getUsers", false) },
            { it.invokeList("getUsers") },
            { it.invokeList("getProfiles", false) },
            { it.invokeList("getProfiles", true, false) },
        )
        val result = probes.asSequence().mapNotNull { probe ->
            service?.let { runCatching { probe(it) }.getOrNull() }
        }.firstOrNull() ?: emptyList<Any>()
        val profiles = result.mapNotNull { item -> item?.let(::toProfile) }
        if (profiles.isNotEmpty()) return profiles
        HLog.e(IllegalStateException("Unable to enumerate profiles"))
        return emptyList()
    }

    /** Invoke a list returning method, returns null when the service does not expose the signature. */
    private fun Any.invokeList(name: String, vararg args: Boolean): List<*>? = runCatching {
        javaClass.getMethod(name, *Array(args.size) { Boolean::class.javaPrimitiveType!! })
            .invoke(this, *args.toTypedArray()) as? List<*>
    }.getOrNull()

    /**
     * Enumerate profiles with `cmd user list -v`.
     *
     * Used when the IUserManager interface of the ROM differs from every known one.
     * Lines look like `900: id=900, name=应用分身, type=profile.CLONE, flags=INITIALIZED|PROFILE ...`.
     */
    private fun getUsersFromShell(): List<HUsers.Profile> {
        val (code, output) = shell("cmd user list -v")
        if (code != 0) throw IllegalStateException("cmd user list failed: $output")
        return output.orEmpty().lineSequence().mapNotNull { line ->
            val id = Regex("""^\s*(\d+):\s*id=(\d+),\s*name=(.*?),\s*type=""").find(line)
                ?: return@mapNotNull null
            // Group 1 is the line index (`0: id=0`, `1: id=900`), group 2 is the real user id
            val userId = id.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val name = id.groupValues[3]
            val flags = Regex("""flags=([\w|]*)""").find(line)?.groupValues?.get(1).orEmpty()
            val state = line.substringAfter("flags=", "")
            HUsers.Profile(
                userId,
                name,
                flags = flagsOf(flags),
                isQuietModeEnabled = state.contains("quiet", ignoreCase = true)
            )
        }.toList()
    }

    private fun toProfile(userInfo: Any): HUsers.Profile? = try {
        val clazz = userInfo.javaClass
        val id = clazz.getField("id").getInt(userInfo)
        val name = runCatching { clazz.getField("name").get(userInfo) as? String }.getOrNull()
            ?: "User $id"
        val flags = runCatching { clazz.getField("flags").getInt(userInfo) }.getOrDefault(0)
        // Not every release exposes the quiet mode state on UserInfo
        val quiet = runCatching { clazz.getField("isQuietModeEnabled").getBoolean(userInfo) }
            .getOrDefault(false)
        HUsers.Profile(id, name, flags, quiet)
    } catch (e: Throwable) {
        HLog.e(e)
        null
    }

    /** Resolve the launch intent of an app in the given profile. */
    fun getLaunchIntentForPackage(packageName: String, userId: Int): Intent? = runCatching {
        if (userId == HPackages.myUserId) return app.packageManager.getLaunchIntentForPackage(packageName)
        // Same source as the app list: the launcher entry of the profile the app lives in
        app.getSystemService(LauncherApps::class.java).getActivityList(packageName, userHandleOf(userId))
            .firstOrNull()?.let { info ->
                Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = info.componentName
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
    }.getOrElse {
        HLog.e(it)
        null
    }

    /**
     * Start an app of another profile.
     *
     * `am start` often exits with 0 even when nothing was started, so the output is inspected
     * as well. [Intent.FLAG_ACTIVITY_NEW_TASK] is implied when launching as another user.
     */
    fun startActivityAsUser(intent: Intent, userId: Int): Boolean {
        if (intent.component == null && intent.action == null) return false
        val flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        val command = buildString {
            append("am start --user $userId")
            append(" -f 0x${Integer.toHexString(flags)}")
            intent.component?.let { append(" -n ${it.flattenToShortString()}") }
            intent.action?.let { append(" -a ${it.shellQuote()}") }
            intent.categories?.forEach { append(" -c ${it.shellQuote()}") }
            intent.data?.let { append(" -d ${it.toString().shellQuote()}") }
        }
        val (code, output) = execute(command)
        val text = output.orEmpty()
        return code == 0 && FAILURE_MARKERS.none { text.contains(it, ignoreCase = true) }
    }

    /**
     * Messages `am start` prints when it did not start anything, even with a zero exit code.
     *
     * "Activity not started" is not listed: some ROMs report it while still bringing the
     * requested component to the front, and the resolver page is a valid shell response too.
     */
    private val FAILURE_MARKERS = listOf("Error", "Exception", "Warning: Unable to")

    /** Single-quote a shell argument. */
    private fun String.shellQuote() = "'" + replace("'", "'\\''") + "'"

    fun uninstallApp(packageName: String): Boolean =
        execute("pm ${if (HPackages.canUninstallNormally(packageName)) "uninstall" else "uninstall --user current"} $packageName").first == 0

    fun reinstallApp(packageName: String): Boolean =
        execute("pm install-existing --user current $packageName").first == 0

    /**
     * Run a command, through su in the root working modes and through the Shizuku service
     * otherwise. The root modes do not run Shizuku at all.
     */
    fun execute(command: String, root: Boolean = isRoot): Pair<Int, String?> {
        if (HailData.workingMode in ROOT_MODES) return HShell.execute(command, true)
        // Shizuku may still be starting, e.g. right after boot, instead of throwing
        if (!Shizuku.pingBinder()) return 1 to "binder haven't been received"
        return runCatching {
            IShizukuService.Stub.asInterface(Shizuku.getBinder())
                .newProcess(arrayOf(if (root) "su" else "sh"), null, null).run {
                    ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                        it.write(command.toByteArray())
                    }
                    waitFor() to inputStream.text.ifBlank { errorStream.text }.also { destroy() }
                }
        }.getOrElse { 1 to it.stackTraceToString() }
    }

    /** Working modes that only have su, Shizuku is not involved. */
    private val ROOT_MODES = setOf(
        HailData.MODE_SU_STOP, HailData.MODE_SU_DISABLE, HailData.MODE_SU_HIDE, HailData.MODE_SU_SUSPEND,
        HailData.MODE_JAILBREAK_DISABLE
    )

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this).bufferedReader().use { it.readText() }
}
