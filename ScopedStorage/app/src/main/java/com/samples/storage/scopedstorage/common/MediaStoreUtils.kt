package com.samples.storage.scopedstorage.common

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat.checkSelfPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object MediaStoreUtils {
    /**
     * Check if the app can writes on the shared storage
     *
     * On Android 10 (API 29), we can add media to MediaStore without having to request the
     * [WRITE_EXTERNAL_STORAGE] permission, so we only check on pre-API 29 devices
     */
    fun canWriteInMediaStore(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            checkSelfPermission(
                context,
                WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * We create a MediaStore [Uri] where an image will be stored
     */
    suspend fun createImageUri(context: Context, filename: String): Uri? {
        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        return withContext(Dispatchers.IO) {
            val newImage = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            }

            // This method will perform a binder transaction which is better to execute off the main
            // thread
            return@withContext context.contentResolver.insert(imageCollection, newImage)
        }
    }

    /**
     * We create a MediaStore [Uri] where a video will be stored
     */
    suspend fun createVideoUri(context: Context, filename: String): Uri? {
        val videoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        return withContext(Dispatchers.IO) {
            val newVideo = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            }

            // This method will perform a binder transaction which is better to execute off the main
            // thread
            return@withContext context.contentResolver.insert(videoCollection, newVideo)
        }
    }

    /**
     * Convert a media [Uri] to a content [Uri] to be used when requesting [FileColumns] values.
     *
     * Some columns are only available on the [MediaStore.Files] collection and this method converts
     * [Uri] from other MediaStore collections (e.g. [MediaStore.Images])
     *
     * @param uri [Uri] representing the MediaStore entry.
     */
    private fun convertMediaUriToContentUri(uri: Uri): Uri? {
        val entryId = uri.lastPathSegment ?: return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.getVolumeName(uri), entryId.toLong())
        } else {
            MediaStore.Files.getContentUri(uri.pathSegments[0], entryId.toLong())
        }
    }

    /**
     * Get file path from a [MediaStore] [Uri]
     */
    private suspend fun getPathByUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Files.FileColumns.DATA),
            null,
            null,
            null
        ) ?: throw Exception("Uri $uri could not be found")

        cursor.use {
            if (!cursor.moveToFirst()) {
                throw Exception("Uri $uri could not be found")
            }

            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA))
        }
    }

    /**
     * Scan file path in [MediaStore] using [MediaScannerConnection]
     */
    suspend fun scanFilePath(context: Context, path: String, mimeType: String): Uri? {
        return suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType)) { _, scannedUri ->
                if (scannedUri == null) {
                    throw Exception("File $path could not be scanned")
                }

                continuation.resume()

                continuation.resume(scannedUri)
            }
        }
    }

    /**
     * Returns a [FileResource] if it finds its [Uri] in MediaStore.
     */
    suspend fun getResourceByUri(context: Context, uri: Uri): FileResource =
        withContext(Dispatchers.IO) {
            // Convert generic media uri to content uri to get FileColumns.MEDIA_TYPE value
            val contentUri = convertMediaUriToContentUri(uri)

            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATA,
            )

            val cursor = contentUri?.let {
                context.contentResolver.query(
                    it,
                    projection,
                    null,
                    null,
                    null
                )
            } ?: throw Exception("Uri $uri could not be found")

            cursor.use {
                if (!cursor.moveToFirst()) {
                    throw Exception("Uri $uri could not be found")
                }

                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val displayNameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mediaTypeColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val mimeTypeColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

                FileResource(
                    id = cursor.getInt(idColumn),
                    uri = contentUri,
                    filename = cursor.getString(displayNameColumn),
                    size = cursor.getLong(sizeColumn),
                    type = FileType.getEnum(cursor.getInt(mediaTypeColumn)),
                    mimeType = cursor.getString(mimeTypeColumn),
                    path = cursor.getString(dataColumn),
                )
            }
        }
}