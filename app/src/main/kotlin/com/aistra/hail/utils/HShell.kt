package com.aistra.hail.utils

import android.os.Build
import androidx.annotation.RequiresApi

object HShell {
    private fun getCurrentUserId(): Int = execSU("am get-current-user").second?.trim()?.toIntOrNull() ?: 0

    private val userArg: String
        get() = "--user ${getCurrentUserId()}"

    /** `--user` argument of a shell command, omitted for the current user. */
    private fun userArg(userId: Int): String =
        if (userId == getCurrentUserId()) "" else "--user $userId"

    /** Joins the command parts and drops the empty ones. */
    private fun command(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString(" ")

    fun execute(command: String, root: Boolean): Pair<Int, String?> = runCatching {
        ProcessBuilder(if (root) "su" else "sh").redirectErrorStream(true).start().run {
            outputStream.use { it.write(command.toByteArray()) }
            waitFor() to inputStream.bufferedReader().use { it.readText() }.also { destroy() }
        }
    }.getOrElse { 1 to it.stackTraceToString() }

    private fun execSU(command: String) = execute(command, true)

    val checkSU get() = execSU("whoami").first == 0

    val lockScreen get() = execSU("input keyevent KEYCODE_POWER").first == 0

    fun forceStopApp(packageName: String, userId: Int = HPackages.myUserId): Boolean =
        execSU(command("am force-stop", userArg(userId), packageName)).first == 0

    fun setAppDisabled(packageName: String, disabled: Boolean, userId: Int = HPackages.myUserId): Boolean =
        execSU(command("pm ${if (disabled) "disable" else "enable"}", userArg(userId), packageName)).first == 0

    fun setAppHidden(packageName: String, hidden: Boolean, userId: Int = HPackages.myUserId): Boolean =
        execSU(command("pm ${if (hidden) "hide" else "unhide"}", userArg(userId), packageName)).first == 0

    fun setAppSuspended(packageName: String, suspended: Boolean, userId: Int = HPackages.myUserId): Boolean =
        execSU(command("pm ${if (suspended) "suspend" else "unsuspend"}", userArg(userId), packageName)).first == 0

    fun uninstallApp(packageName: String) = execSU(
        "pm ${if (HPackages.canUninstallNormally(packageName)) "uninstall" else "uninstall $userArg"} $packageName"
    ).first == 0

    fun reinstallApp(packageName: String) = execSU("pm install-existing $userArg $packageName").first == 0

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean) = execSU(
        "appops set $userArg $packageName RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}"
    ).first == 0

    fun grantPermission(packageName: String, permission: String) =
        execSU("pm grant $userArg $packageName $permission").first == 0
}