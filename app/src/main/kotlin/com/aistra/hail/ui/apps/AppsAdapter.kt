package com.aistra.hail.ui.apps

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ItemAppsBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

class AppsAdapter : ListAdapter<AppListItem, AppsAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppListItem>() {
            override fun areItemsTheSame(
                oldItem: AppListItem, newItem: AppListItem
            ): Boolean = oldItem.userId == newItem.userId && oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppListItem, newItem: AppListItem): Boolean =
                oldItem.applicationInfo.flags == newItem.applicationInfo.flags
        }
    }

    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemCheckedChangeListener: OnItemCheckedChangeListener
    private var loadIconJob: Job? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemAppsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = currentList[position]
        holder.bindInfo(info)
    }

    fun onDestroy() {
        if (loadIconJob?.isActive == true) loadIconJob?.cancel()
    }

    inner class ViewHolder(private val binding: ItemAppsBinding) : RecyclerView.ViewHolder(binding.root) {
        lateinit var item: AppListItem
        private val info get() = item.applicationInfo
        private val pkg get() = item.packageName

        /**
         * Flag that view data is being updated to avoid triggering the event.
         * */
        private var updating = false

        init {
            binding.root.apply {
                setOnClickListener { onItemClickListener.onItemClick(binding.appStar) }
                isLongClickable = true
            }
            binding.appStar.setOnCheckedChangeListener { button, isChecked ->
                if (!updating) onItemCheckedChangeListener.onItemCheckedChange(
                    button, isChecked, item.userId, pkg
                )
            }
        }

        fun bindInfo(item: AppListItem) {
            updating = true
            this.item = item
            val frozen = AppManager.isAppFrozen(item.userId, pkg)

            binding.appIcon.apply {
                loadIconJob = AppIconCache.loadIconBitmapAsync(
                    context, info, item.userId, this, HailData.grayscaleIcon && frozen
                )
            }
            binding.appName.apply {
                // Name and icon are taken from the profile's own ApplicationInfo, the system
                // already distinguishes apps of different profiles
                // The shell fallback of other profiles only knows package names,
                // fall back to the installed app of this profile for a proper label
                val name = info.takeIf { it.name != it.packageName }
                    ?.loadLabel(context.packageManager)?.toString()
                    ?: HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(context.packageManager)?.toString()
                    ?: pkg
                text = if (!HailData.grayscaleIcon && frozen) "❄️$name" else name
                isEnabled = !HailData.grayscaleIcon || !frozen
                if (HPackages.isAppUninstalled(pkg, item.userId)) setTextColor(
                    MaterialColors.getColor(
                        this, androidx.appcompat.R.attr.colorError
                    )
                )
                else setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            }
            binding.appDesc.apply {
                text = pkg
                isEnabled = !HailData.grayscaleIcon || !frozen
            }
            binding.appStar.apply {
                isChecked = HailData.isChecked(item.userId, pkg)
                isEnabled = item.isCurrentUser || HailData.workingMode != HailData.MODE_DEFAULT
            }
            updating = false
        }
    }

    interface OnItemClickListener {
        fun onItemClick(buttonView: CompoundButton)
    }

    interface OnItemCheckedChangeListener {
        fun onItemCheckedChange(
            buttonView: CompoundButton, isChecked: Boolean, userId: Int, packageName: String
        )
    }
}
