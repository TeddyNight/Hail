package com.aistra.hail.work

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HPackages
import java.util.concurrent.TimeUnit

object HWork {
    fun cancelWork(name: String) =
        WorkManager.getInstance(app).cancelUniqueWork(name)

    fun setDeferredFrozen(
        packageName: String, userId: Int = HPackages.myUserId, frozen: Boolean = true, minutes: Long
    ) {
        WorkManager.getInstance(app).enqueueUniqueWork(
            uniqueName(userId, packageName),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FrozenWorker>().setInputData(
                workDataOf(
                    HailData.KEY_PACKAGE to packageName,
                    HailData.KEY_USER to userId,
                    HailData.KEY_FROZEN to frozen
                )
            ).setInitialDelay(minutes, TimeUnit.MINUTES).build()
        )
    }

    /** A deferred task is unique per package, not per profile, so keep the old name for the current user. */
    fun uniqueName(userId: Int, packageName: String) =
        if (userId == HPackages.myUserId) packageName else "$userId:$packageName"

    fun setAutoFreeze(screenOff: Boolean) {
        WorkManager.getInstance(app).enqueueUniqueWork(
            HailApi.ACTION_FREEZE_ALL,
            ExistingWorkPolicy.REPLACE,  // in case the old task has not been executed...
            OneTimeWorkRequestBuilder<AutoFreezeWorker>().run {
                if (screenOff) setInitialDelay(HailData.autoFreezeDelay, TimeUnit.MINUTES)
                setInputData(workDataOf(HailData.ACTION_LOCK to screenOff))
                build()
            }
        )
    }
}