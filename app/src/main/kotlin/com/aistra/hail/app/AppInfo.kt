package com.aistra.hail.app

import android.content.pm.ApplicationInfo
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HPackages

class AppInfo(
    val userId: Int,
    val packageName: String,
    var pinned: Boolean = false,
    var whitelisted: Boolean = false,
    val tagIdList: MutableList<Int> = mutableListOf(0)
) {
    enum class State { NOT_FOUND, UNFROZEN, FROZEN }

    val applicationInfo: ApplicationInfo? get() = HPackages.getApplicationInfoOrNull(packageName, userId)
    val name get() = applicationInfo?.loadLabel(app.packageManager) ?: packageName
    val isCurrentUser get() = userId == HPackages.myUserId
    val state
        get() = when {
            applicationInfo == null -> State.NOT_FOUND
            AppManager.isAppFrozen(userId, packageName) -> State.FROZEN
            else -> State.UNFROZEN
        }

    override fun equals(other: Any?): Boolean =
        other is AppInfo && other.userId == userId && other.packageName == packageName

    override fun hashCode(): Int = userId * 31 + packageName.hashCode()

    override fun toString(): String = "$userId:$packageName"

    companion object {
        /** The identifier of an app in a profile. */
        data class Key(val userId: Int, val packageName: String)

        fun keyOf(userId: Int, packageName: String) = Key(userId, packageName)
    }
}
