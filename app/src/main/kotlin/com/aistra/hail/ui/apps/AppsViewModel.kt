package com.aistra.hail.ui.apps

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.*
import kotlinx.coroutines.*

/** An installed app together with the profile it belongs to. */
data class AppListItem(val userId: Int, val applicationInfo: ApplicationInfo) {
    val packageName get() = applicationInfo.packageName
    val isCurrentUser get() = userId == HPackages.myUserId
}

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    val apps = MutableLiveData<List<AppListItem>>()
    val isRefreshing = MutableLiveData(false)
    val query = MutableLiveData("")
    val displayApps = MutableLiveData<List<AppListItem>>()

    init {
        updateAppList()
    }

    private var refreshJob: Job? = null
    private var refreshStateJob: Job? = null

    /**
     * Delaying changes to the refreshing state prevents the progress bar from flickering.
     * */
    private fun postRefreshState(state: Boolean, delayTime: Long = 200L) {
        if (!state) {
            refreshStateJob?.cancel()
            isRefreshing.postValue(false)
        } else if (refreshStateJob == null || refreshStateJob!!.isCompleted) {
            refreshStateJob = viewModelScope.launch {
                delay(delayTime)
                isRefreshing.postValue(true)
            }
        }
    }

    fun postQuery(text: String, delayTime: Long = 300L) {
        refreshJob?.cancel()
        if (delayTime == 0L)
            query.postValue(text)
        else {
            refreshJob = viewModelScope.launch {
                delay(delayTime)
                query.postValue(text)
            }
        }
    }

    /**
     * This method is only used to refresh all the applications that the user has installed
     * and has no filtering or sorting effect.
     * */
    fun updateAppList() {
        viewModelScope.launch {
            postRefreshState(true)
            apps.postValue(loadApps())
        }
    }

    /**
     * Load the apps of the current profile, plus the other profiles when enabled.
     *
     * The current profile uses `PackageManager`, every other profile uses `LauncherApps`.
     * A profile that cannot be queried is skipped instead of hiding the others.
     */
    private suspend fun loadApps(): List<AppListItem> = withContext(Dispatchers.IO) {
        val current = HPackages.getInstalledApplications()
            .map { AppListItem(HPackages.myUserId, it) }
        if (!HailData.showOtherProfiles || !HUsers.canListOtherProfiles) return@withContext current
        val others = HUsers.getOtherProfiles().map { profile ->
            async {
                HShizuku.getLauncherActivities(profile.id)
                    .map { AppListItem(profile.id, it) }
            }
        }.awaitAll().flatten()
        current + others
    }

    /**
     * The list that the user actually sees.
     *
     * This method is different from `updateAppList()` in that it filters and rearranges the data
     * from `apps` and places it in `displayApps`.
     * */
    fun updateDisplayAppList() {
        apps.value?.let {
            viewModelScope.launch {
                postRefreshState(true)
                displayApps.postValue(filterList(it, query.value))
                postRefreshState(false)
            }
        }
    }


    private val ApplicationInfo.isSystemApp: Boolean
        get() = flags and ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM

    private suspend fun filterList(
        appList: List<AppListItem>,
        query: String?
    ): List<AppListItem> {
        val pm = getApplication<HailApp>().packageManager
        val myUserId = HPackages.myUserId
        return withContext(Dispatchers.Default) {
            return@withContext appList.filter {
                val info = it.applicationInfo
                ((HailData.filterUserApps && !info.isSystemApp)
                        || (HailData.filterSystemApps && info.isSystemApp))

                        && ((HailData.filterFrozenApps && AppManager.isAppFrozen(it.userId, it.packageName))
                        || (HailData.filterUnfrozenApps && !AppManager.isAppFrozen(it.userId, it.packageName)))
                        // Search apps
                        && ((HailData.nineKeySearch
                        && (NineKeySearch.search(query, info.packageName, info.loadLabel(pm).toString())))
                        || FuzzySearch.search(info.packageName, query)
                        || FuzzySearch.search(info.loadLabel(pm).toString(), query)
                        || PinyinSearch.searchPinyinAll(info.loadLabel(pm).toString(), query))
            }.run {
                when (HailData.sortBy) {
                    // Install/update times are only available for the current profile
                    HailData.SORT_INSTALL -> sortedBy {
                        if (it.userId != myUserId) 0L
                        else HPackages.getUnhiddenPackageInfoOrNull(it.packageName)?.firstInstallTime ?: 0
                    }

                    HailData.SORT_UPDATE -> sortedByDescending {
                        if (it.userId != myUserId) 0L
                        else HPackages.getUnhiddenPackageInfoOrNull(it.packageName)?.lastUpdateTime ?: 0
                    }

                    else -> sortedWith(Comparator { a, b ->
                        NameComparator.compare(a.applicationInfo, b.applicationInfo)
                    })
                }
            }
        }
    }
}