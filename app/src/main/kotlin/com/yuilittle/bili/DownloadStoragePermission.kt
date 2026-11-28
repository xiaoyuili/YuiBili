package com.yuilittle.bili

import android.app.Activity
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast

/** 两个下载管理宿主共用请求结果；只在用户主动选择公共库、重试或删除时申请。 */
@Suppress("DEPRECATION")
class DownloadStoragePermission : Fragment() {
    private var onGranted: (() -> Unit)? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST) return
        val action = onGranted
        onGranted = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
        else activity?.let { Toast.makeText(it, "未获存储权限，未更改任务或保存位置", Toast.LENGTH_LONG).show() }
        fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
    }

    companion object {
        private const val REQUEST = 1
        private const val TAG = "download_library_permission"

        internal fun withAccess(context: Context, action: () -> Unit) {
            if (!DownloadMediaLibrary.needsPermission(context)) { action(); return }
            val host = context as? Activity ?: return
            if (host.isFinishing || host.isDestroyed) return
            val fm = host.fragmentManager
            if (fm.findFragmentByTag(TAG) != null) return
            val request = DownloadStoragePermission().also { it.onGranted = action }
            fm.beginTransaction().add(request, TAG).commit()
            fm.executePendingTransactions()
            request.requestPermissions(DownloadMediaLibrary.legacyPermissions, REQUEST)
        }
    }
}
