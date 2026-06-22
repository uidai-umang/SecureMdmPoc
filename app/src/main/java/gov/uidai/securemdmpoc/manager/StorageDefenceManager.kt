package gov.uidai.securemdmpoc.manager

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import gov.uidai.securemdmpoc.data.repository.AppManagementRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Detects new media/download entries landing on the device that
 * were not created by our own app, and deletes them.
 *
 * IMPORTANT — documented limitation, not a guarantee:
 * MediaStore writes performed via ContentResolver.insert() do not
 * require any runtime storage permission under Scoped Storage, so
 * this is a detect-and-delete-after-the-fact mechanism, not a
 * preventative block. It closes the gap left by permission denial
 * (see DynamicAppManager.denyStoragePermissions), it does not
 * replace it.
 */
class StorageDefenceManager(
    private val context: Context,
    private val repository: AppManagementRepository
) {
    private val TAG = "StorageDefenceManager"
    private var mediaStoreObserver: ContentObserver? = null

    fun startDetection() {
        val handler = Handler(Looper.getMainLooper())

        mediaStoreObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri ?: return
                inspectNewEntry(uri)
            }
        }

        val resolver = context.contentResolver

        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!
        )
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.registerContentObserver(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!
            )
        }

        Log.d(TAG, "MediaStore observer registered")
    }

    fun stopDetection() {
        mediaStoreObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        mediaStoreObserver = null
        Log.d(TAG, "MediaStore observer stopped")
    }

    private fun inspectNewEntry(uri: Uri) {
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE
                ),
                null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val name = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    val path = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                    val size = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))

                    if (path != null && !isOurAppFile(path)) {
                        Log.w(TAG, "Unauthorised media entry: $name ($size bytes)")
                        report("UNAUTHORISED_MEDIA", name ?: "unknown", size)
                        deleteEntry(uri, path)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore inspection failed: ${e.message}")
        }
    }

    private fun deleteEntry(uri: Uri, filePath: String) {
        try {
            context.contentResolver.delete(uri, null, null)
            val file = File(filePath)
            if (file.exists()) file.delete()
            Log.d(TAG, "Deleted: $filePath")
        } catch (e: Exception) {
            Log.w(TAG, "Delete failed: ${e.message}")
        }
    }

    private fun isOurAppFile(filePath: String): Boolean {
        val ourDirs = listOfNotNull(
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath
        )
        return ourDirs.any { filePath.startsWith(it) }
    }

    private fun report(action: String, fileName: String, size: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.reportApps(action, listOf("$fileName (${size}b)"))
            } catch (e: Exception) {
                Log.e(TAG, "Report failed: ${e.message}")
            }
        }
    }
}