package com.aistra.hail.utils

import com.aistra.hail.app.HailData
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Profiles (Android users) that Hail can see and operate on.
 *
 * Reading the apps of another profile only needs `LauncherApps`, which works from the app
 * process. Freezing and launching them additionally needs a working mode that reaches
 * `IPackageManager` as shell/root, which is what [canManageOtherProfiles] tracks.
 */
object HUsers {
    /**
     * A profile (Android user) Hail can operate on.
     *
     * `android.content.pm.UserInfo` is not part of the public SDK stubs,
     * so this mirrors the fields Hail needs.
     */
    data class Profile(
        val id: Int,
        val name: String,
        val flags: Int = 0,
        val isQuietModeEnabled: Boolean = false
    ) {
        val isCurrentUser: Boolean get() = id == HPackages.myUserId
        val isWorkProfile: Boolean get() = flags and FLAG_MANAGED_PROFILE != 0
    }

    val myUserId: Int get() = HPackages.myUserId

    private var cache: List<Profile>? = null
    private var cacheTime = 0L
    private const val CACHE_TTL = 60_000L

    /**
     * Whether the apps of other profiles can be listed.
     *
     * `LauncherApps` is enough for this and does not depend on the working mode.
     */
    val canListOtherProfiles: Boolean get() = true

    /**
     * Whether the apps of other profiles can be frozen, unfrozen and launched.
     *
     * Only the shell/root backed working modes reach another user consistently.
     */
    val canManageOtherProfiles: Boolean
        get() = with(HailData.workingMode) {
            startsWith(HailData.SHIZUKU) || startsWith(HailData.SU) || startsWith(HailData.PRIVAPP)
        }

    /** All profiles Hail may operate on, including the current one. */
    suspend fun getProfiles(): List<Profile> = withContext(Dispatchers.IO) {
        cache?.takeIf {
            it.isNotEmpty() && SystemClock.elapsedRealtime() - cacheTime < CACHE_TTL
        } ?: runCatching {
            // LauncherApps reports the profiles a launcher can reach, which covers work profiles
            // and the app clones created by the ROM alike; the shell query fills in the names
            val shellProfiles = runCatching { HShizuku.getUsers() }.getOrDefault(emptyList())
            val ids = HShizuku.getLauncherProfiles() + shellProfiles.map { it.id }
            ids.filter { it != myUserId }.sorted().map { id ->
                shellProfiles.find { it.id == id } ?: Profile(id, "User $id")
            }.plus(myProfile())
        }.getOrDefault(listOf(myProfile())).also {
            cache = it
            cacheTime = SystemClock.elapsedRealtime()
            // Drop the apps of profiles that went away, e.g. a removed work profile
            HailData.pruneUnknownUsers(it.map { profile -> profile.id }.toSet())
        }
    }

    /** Profiles other than the current one that can be reached. Empty when there is none. */
    suspend fun getOtherProfiles(): List<Profile> =
        if (canListOtherProfiles) getProfiles().filter { it.id != myUserId } else emptyList()

    fun getProfile(userId: Int): Profile? = cache?.find { it.id == userId }

    fun clearCache() {
        cache = null
    }

    private fun myProfile() = Profile(myUserId, "User $myUserId")

    /**
     * Whether the given user can be operated on by the current working mode.
     *
     * Listing is separate, a profile can be visible while freezing it is not possible.
     */
    fun isSupported(userId: Int): Boolean = userId == myUserId || canManageOtherProfiles
}

/** Flags of [Profile]. */
const val FLAG_MANAGED_PROFILE = 0x00000020
private const val FLAG_ADMIN = 0x00000002
private const val FLAG_PROFILE = 0x00001000

/** Parse the `flags=A|B|C` column of `cmd user list -v`. */
fun flagsOf(names: String): Int = names.split('|').fold(0) { acc, flag ->
    acc or when (flag.trim()) {
        "ADMIN" -> FLAG_ADMIN
        "DISABLED" -> 0x00000040
        "EPHEMERAL" -> 0x00000400
        "PROFILE" -> FLAG_PROFILE
        "MANAGED_PROFILE" -> FLAG_MANAGED_PROFILE
        else -> 0
    }
}
