package com.geniechatrn.genie

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File
import java.io.FileOutputStream

/**
 * Picking a photo for the vision model.
 *
 * Uses the system document picker rather than a media-library permission: the
 * user chooses one file and the app is granted that file, which is the whole
 * requirement here. Asking for READ_MEDIA_IMAGES would trade a permission
 * prompt for the ability to read every photo on the device.
 *
 * The picked image is COPIED into the app's own storage, for two reasons: the
 * SAF grant does not survive a restart, and llama.cpp's vision path takes a
 * filesystem path -- it cannot open a content:// URI.
 */
class ImagePickerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "ImagePicker"
        private const val REQUEST_CODE = 0x9101

        /**
         * Longest edge of the copy handed to the model.
         *
         * The projector downsamples to a few hundred pixels anyway, so a 12MP
         * phone photo is ~24MB of bitmap decoded for nothing -- on a device
         * that is already holding a 1.15GB model and a 2GB KV cache.
         */
        private const val MAX_EDGE = 1024
    }

    private var pending: Promise? = null

    private val activityListener: ActivityEventListener =
        object : BaseActivityEventListener() {
            override fun onActivityResult(
                activity: Activity?,
                requestCode: Int,
                resultCode: Int,
                data: Intent?,
            ) {
                if (requestCode != REQUEST_CODE) return
                val promise = pending ?: return
                pending = null

                if (resultCode != Activity.RESULT_OK || data?.data == null) {
                    promise.resolve(null)   // cancelled: not an error
                    return
                }
                try {
                    promise.resolve(copyIn(data.data!!))
                } catch (e: Throwable) {
                    Log.e(TAG, "failed to copy picked image", e)
                    promise.reject("pick_failed", e.message, e)
                }
            }
        }

    init {
        reactContext.addActivityEventListener(activityListener)
    }

    override fun getName() = "ImagePicker"

    /** Resolves with an absolute file path, or null if the user backed out. */
    @ReactMethod
    fun pickImage(promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("no_activity", "No activity to launch the picker from")
            return
        }
        if (pending != null) {
            promise.reject("busy", "A picker is already open")
            return
        }
        pending = promise
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            activity.startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Throwable) {
            pending = null
            promise.reject("no_picker", "No image picker available", e)
        }
    }

    /** Decode, shrink if needed, and write a JPEG into filesDir/attachments. */
    private fun copyIn(uri: Uri): String {
        val dir = File(reactContext.filesDir, "attachments").apply { mkdirs() }
        val target = File(dir, "img-${System.currentTimeMillis()}.jpg")

        // Copy the raw bytes to a plain file FIRST, then decode from disk.
        //
        // openInputStream returns null for MediaProvider's documents URIs
        // (content://com.android.providers.media.documents/...) often enough
        // that it cannot be relied on -- measured on this device. A
        // ParcelFileDescriptor via openFileDescriptor is the path that works,
        // and decoding a real file also lets the two-pass downsample reread the
        // bytes without reopening the provider.
        val raw = File(dir, "raw-${System.currentTimeMillis()}.bin")
        try {
            val pfd = reactContext.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Cannot open $uri")
            pfd.use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { input ->
                    raw.outputStream().use { input.copyTo(it, 1 shl 20) }
                }
            }

            // Bounds first, so a large photo is subsampled during decode rather
            // than after -- keeps peak memory down on a device already holding
            // a 1.15GB model.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.absolutePath, bounds)

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap: Bitmap = BitmapFactory.decodeFile(raw.absolutePath, options)
                ?: error("Cannot decode $uri")

            FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            Log.i(TAG, "attachment ${target.name} ${bounds.outWidth}x${bounds.outHeight} /$sample")
            return target.absolutePath
        } finally {
            raw.delete()
        }
    }
}
