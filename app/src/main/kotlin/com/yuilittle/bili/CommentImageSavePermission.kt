package com.yuilittle.bili

import android.app.Activity
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

@Suppress("DEPRECATION")
class CommentImageSavePermission : Fragment() {
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        val host = activity
        val url = arguments?.getString(URL).orEmpty()
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (host != null && !host.isFinishing && !host.isDestroyed && url.isNotBlank()) {
                ImagePreviewDialog.downloadImageToGallery(host, url)
            }
        } else host?.let { Toast.makeText(it, "未获存储权限，图片未保存", Toast.LENGTH_SHORT).show() }
        fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
    }

    companion object {
        private const val REQUEST_CODE = 0xC011
        private const val TAG = "comment_image_save_permission"
        private const val URL = "image_url"

        fun requestIfNeeded(context: Context, url: String): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            ) return false
            val activity = context as? Activity ?: return true
            if (activity.isFinishing || activity.isDestroyed) return true
            val manager = activity.fragmentManager
            val pending = manager.findFragmentByTag(TAG) as? CommentImageSavePermission
            if (pending != null) {
                pending.arguments?.putString(URL, url)
                return true
            }
            val request = CommentImageSavePermission().apply { arguments = Bundle().apply { putString(URL, url) } }
            manager.beginTransaction().add(request, TAG).commit()
            manager.executePendingTransactions()
            request.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE)
            return true
        }
    }
}
