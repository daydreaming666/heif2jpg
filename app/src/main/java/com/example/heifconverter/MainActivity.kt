package com.example.heifconverter

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents
import androidx.appcompat.app.AppCompatActivity
import com.vmadalin.easypermissions.EasyPermissions
import java.io.IOException
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    val bSelect: Button by lazy { findViewById(R.id.select_img_button) }

    companion object {
        val PERMISSIONS = arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

        const val RC_PERMISSION_READ_EXTERNAL_STORAGE = 1
    }

    private val getContent = registerForActivityResult(GetMultipleContents()) {
        if (it.isNotEmpty()) {
            Toast.makeText(this, "Selected ${it.size} images", Toast.LENGTH_SHORT).show()
            it.forEach {
                thread {
                    Looper.prepare()
                    convert(it)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initPermissions()

        bSelect.setOnClickListener {
            getContent.launch("image/*")
        }

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                intent.data ?: intent.getParcelableExtra<Uri?>(Intent.EXTRA_STREAM)?.let {
                    thread {
                        Looper.prepare()
                        convert(it)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM)?.forEach {
                    thread {
                        Looper.prepare()
                        convert(it)
                    }
                }
            }

        }

    }

    private fun initPermissions() {
        if (!EasyPermissions.hasPermissions(this, *PERMISSIONS)) {
            // Do not have permissions, request them now
            EasyPermissions.requestPermissions(
                this, getString(R.string.permission_rationale), RC_PERMISSION_READ_EXTERNAL_STORAGE, *PERMISSIONS
            )
        }
    }

    private fun convert(uri: Uri) {
        val source = ImageDecoder.createSource(this.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source)

        try {
            saveBitmap(this, bitmap, Bitmap.CompressFormat.JPEG,
                "image/jpeg", uri.lastPathSegment + ".jpeg")
        } catch (e: IOException) {
            Log.d("convert", e.toString())
            Toast.makeText(this, "Error: $e", Toast.LENGTH_SHORT).show()
        }
        Toast.makeText(this, "Saved to ${uri.lastPathSegment}", Toast.LENGTH_SHORT).show()
    }

    @Throws(IOException::class)
    fun saveBitmap(
        context: Context, bitmap: Bitmap, format: Bitmap.CompressFormat, mimeType: String, displayName: String
    ): Uri {

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
        }

        var uri: Uri? = null

        return runCatching {
            with(context.contentResolver) {
                insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.also {
                    uri = it // Keep uri reference so it can be removed on failure

                    openOutputStream(it)?.use { stream ->
                        if (!bitmap.compress(format, 100, stream)) throw IOException("Failed to save bitmap.")
                    } ?: throw IOException("Failed to open output stream.")

                } ?: throw IOException("Failed to create new MediaStore record.")
            }
        }.getOrElse {
            uri?.let { orphanUri ->
                // Don't leave an orphan entry in the MediaStore
                context.contentResolver.delete(orphanUri, null, null)
            }

            throw it
        }
    }
}
