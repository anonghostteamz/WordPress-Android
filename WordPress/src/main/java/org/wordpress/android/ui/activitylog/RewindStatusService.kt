package org.wordpress.android.ui.activitylog

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.util.Log
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.greenrobot.eventbus.ThreadMode.BACKGROUND
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.ActivityLogActionBuilder
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.activity.ActivityLogModel
import org.wordpress.android.fluxc.model.activity.RewindStatusModel
import org.wordpress.android.fluxc.model.activity.RewindStatusModel.Rewind
import org.wordpress.android.fluxc.model.activity.RewindStatusModel.Rewind.Status
import org.wordpress.android.fluxc.model.activity.RewindStatusModel.Rewind.Status.RUNNING
import org.wordpress.android.fluxc.model.activity.RewindStatusModel.State.ACTIVE
import org.wordpress.android.fluxc.store.ActivityLogStore
import org.wordpress.android.fluxc.store.ActivityLogStore.FetchRewindStatePayload
import org.wordpress.android.fluxc.store.ActivityLogStore.OnRewind
import org.wordpress.android.fluxc.store.ActivityLogStore.OnRewindStatusFetched
import org.wordpress.android.fluxc.store.ActivityLogStore.RewindError
import org.wordpress.android.fluxc.store.ActivityLogStore.RewindPayload
import org.wordpress.android.fluxc.store.ActivityLogStore.RewindStatusError
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RewindStatusService
@Inject
constructor(
    private val activityLogStore: ActivityLogStore,
    private val rewindProgressChecker: RewindProgressChecker,
    private val dispatcher: Dispatcher
) {
    private val mutableRewindAvailable = MutableLiveData<Boolean>()
    private val mutableRewindError = MutableLiveData<RewindError>()
    private val mutableRewindStatusFetchError = MutableLiveData<RewindStatusError>()
    private val mutableRewindProgress = MutableLiveData<RewindProgress>()
    private var site: SiteModel? = null
    private var activityLogModelItem: ActivityLogModel? = null

    val rewindAvailable: LiveData<Boolean> = mutableRewindAvailable
    val rewindError: LiveData<RewindError> = mutableRewindError
    val rewindStatusFetchError: LiveData<RewindStatusError> = mutableRewindStatusFetchError
    val rewindProgress: LiveData<RewindProgress> = mutableRewindProgress

    val isRewindInProgress: Boolean
        get() = rewindProgress.value?.status == Status.RUNNING

    val isRewindAvailable: Boolean
        get() = rewindAvailable.value == true

    fun rewind(rewindId: String, site: SiteModel) {
        dispatcher.dispatch(ActivityLogActionBuilder.newRewindAction(RewindPayload(site, rewindId)))
        updateRewindProgress(rewindId, 0, RUNNING)
        mutableRewindAvailable.postValue(false)
        mutableRewindError.postValue(null)
    }

    fun start(site: SiteModel) {
        this.site = site
        dispatcher.register(this)
        requestStatusUpdate()
        reloadRewindStatus()
    }

    fun stop() {
        dispatcher.unregister(this)
        site = null
    }

    fun requestStatusUpdate() {
        site?.let {
            Log.d("rewind_service ", "requestStatusUpdate")
            dispatcher.dispatch(ActivityLogActionBuilder.newFetchRewindStateAction(FetchRewindStatePayload(it)))
        }
    }

    private fun reloadRewindStatus(): Boolean {
        site?.let {
            val state = activityLogStore.getRewindStatusForSite(it)
            state?.let {
                Log.d("rewind_service ", "Reloading rewind status")
                updateRewindStatus(state)
                return true
            }
        }
        return false
    }

    private fun updateRewindStatus(rewindStatus: RewindStatusModel?) {
        mutableRewindAvailable.postValue(rewindStatus?.state == ACTIVE && rewindStatus.rewind?.status != RUNNING)

        val rewind = rewindStatus?.rewind
        Log.d("rewind_service ", "Updating rewind progress: $rewind")
        if (rewind != null) {
            val restoreId = rewindStatus.rewind?.restoreId
            if (!rewindProgressChecker.isRunning && restoreId != null) {
                site?.let { rewindProgressChecker.startNow(it, restoreId) }
            }
            updateRewindProgress(rewind.rewindId, rewind.progress, rewind.status, rewind.reason)
            if (rewind.status != RUNNING) {
                rewindProgressChecker.cancel()
            }
        } else {
            mutableRewindProgress.postValue(null)
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    @SuppressWarnings("unused")
    fun onRewindStatusFetched(event: OnRewindStatusFetched) {
        Log.d("rewind_service ", "onRewindStatusFetched")
        mutableRewindStatusFetchError.postValue(event.error)
        if (event.isError) {
            rewindProgressChecker.cancel()
        }
        reloadRewindStatus()
    }

    @Subscribe(threadMode = BACKGROUND)
    @SuppressWarnings("unused")
    fun onRewind(event: OnRewind) {
        Log.d("rewind_service ", "onRewind")
        mutableRewindError.postValue(event.error)
        if (event.isError) {
            mutableRewindAvailable.postValue(true)
            reloadRewindStatus()
            updateRewindProgress(event.rewindId, 0, Status.FAILED, event.error?.type?.toString())
            return
        }
        site?.let {
            event.restoreId?.let { restoreId ->
                rewindProgressChecker.start(it, restoreId)
            }
        }
    }

    private fun updateRewindProgress(
        rewindId: String?,
        progress: Int?,
        rewindStatus: Rewind.Status,
        rewindError: String? = null
    ) {
        Log.d("rewind_service ", "Cached activity - ${activityLogModelItem?.activityID}, rewindId: $rewindId, cached rewindID: ${activityLogModelItem?.rewindID}")
        var activityItem = if (rewindId != null) activityLogStore.getActivityLogItemByRewindId(rewindId) else null
        if (activityItem == null && activityLogModelItem != null && activityLogModelItem?.rewindID == rewindId) {
            activityItem = activityLogModelItem
        }
        if (activityItem != null) {
            activityLogModelItem = activityItem
        }
        Log.d("rewind_service ", "Cached activity - After loading - ${activityItem?.activityID}, rewindId: $rewindId, cached rewindID: ${activityItem?.rewindID}")
        val rewindProgress = RewindProgress(
                activityItem,
                progress,
                activityItem?.published,
                rewindStatus,
                rewindError
        )
        Log.d("rewind_service ", "Updating rewind progress: $rewindProgress")
        mutableRewindProgress.postValue(rewindProgress)
    }

    data class RewindProgress(
        val activityLogItem: ActivityLogModel?,
        val progress: Int?,
        val date: Date?,
        val status: Rewind.Status,
        val failureReason: String? = null
    )
}
