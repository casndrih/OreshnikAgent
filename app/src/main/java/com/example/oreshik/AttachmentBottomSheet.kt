package com.example.oreshik

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File

/** Attachment picker for camera, photos/videos, arbitrary files and documents. */
class AttachmentBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AttachmentBottomSheet"
        const val RESULT_KEY = "attachment_result"
        const val RESULT_URI = "uri" // Kept for compatibility with old result listeners.
        const val RESULT_URIS = "uris"
        const val RESULT_KIND = "kind"

        private const val STATE_PENDING_KIND = "pending_attachment_kind"
        private const val STATE_CAMERA_URI = "pending_camera_uri"
        private const val STATE_CAMERA_FILE = "pending_camera_file"
        const val KIND_CAMERA = "camera"
        const val KIND_FILE = "file"
        const val KIND_DOCUMENT = "document"
        const val KIND_MEDIA = "media"
    }

    private var pendingKind: String? = null
    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), "Camera permission is needed to take a photo.", Toast.LENGTH_SHORT).show()
    }

    private val attachmentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val kind = pendingKind
        val data = result.data
        val uris = when {
            kind == KIND_CAMERA && pendingCameraFile?.length()?.let { it > 0L } == true -> listOfNotNull(pendingCameraUri)
            kind == KIND_CAMERA -> emptyList()
            result.resultCode != Activity.RESULT_OK -> emptyList()
            data?.clipData != null -> (0 until data.clipData!!.itemCount).mapNotNull { data.clipData!!.getItemAt(it).uri }
            data?.data != null -> listOf(data.data!!)
            else -> emptyList()
        }.distinctBy(Uri::toString)

        if (result.resultCode == Activity.RESULT_OK && uris.isNotEmpty()) {
            publishResult(uris, kind ?: KIND_FILE)
        } else if (kind == KIND_CAMERA) {
            pendingCameraFile?.delete()
        }
        clearPendingCamera()
        pendingKind = null
        dismissAllowingStateLoss()
    }

    private val visualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isNotEmpty()) publishResult(uris, KIND_MEDIA)
        pendingKind = null
        dismissAllowingStateLoss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingKind = savedInstanceState?.getString(STATE_PENDING_KIND)
        pendingCameraUri = savedInstanceState?.getString(STATE_CAMERA_URI)?.let { it.toUri() }
        pendingCameraFile = savedInstanceState?.getString(STATE_CAMERA_FILE)?.let { File(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_KIND, pendingKind)
        outState.putString(STATE_CAMERA_URI, pendingCameraUri?.toString())
        outState.putString(STATE_CAMERA_FILE, pendingCameraFile?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_attachments, container, false)

    override fun onStart() {
        super.onStart()
        val content = view ?: return
        dialog?.let { BottomSheetSurface.clearOuterSurfaces(it, content) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.attachCamera).setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        view.findViewById<View>(R.id.attachFiles).setOnClickListener {
            launchSafely(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                },
                KIND_FILE,
            )
        }
        view.findViewById<View>(R.id.attachGallery).setOnClickListener {
            pendingKind = KIND_MEDIA
            runCatching {
                visualMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            }.onFailure {
                pendingKind = null
                Toast.makeText(requireContext(), "Unable to open the photo and video picker: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
        view.findViewById<View>(R.id.attachDocs).setOnClickListener {
            launchSafely(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/pdf",
                        "application/msword",
                        "application/rtf",
                        "text/rtf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/plain",
                    ))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                },
                KIND_DOCUMENT,
            )
        }
    }

    private fun publishResult(uris: List<Uri>, kind: String) {
        if (uris.isEmpty()) return
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            Bundle().apply {
                putStringArrayList(RESULT_URIS, ArrayList(uris.map(Uri::toString)))
                putString(RESULT_URI, uris.first().toString())
                putString(RESULT_KIND, kind)
            },
        )
    }

    private fun launchSafely(intent: Intent, kind: String) {
        runCatching {
            pendingKind = kind
            attachmentLauncher.launch(intent)
        }.onFailure {
            pendingKind = null
            if (kind == KIND_CAMERA) {
                pendingCameraFile?.delete()
                clearPendingCamera()
            }
            Toast.makeText(requireContext(), "Unable to open attachment picker: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchCamera() {
        val ctx = requireContext()
        val cameraDir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir, "oreshik_camera")
        val photoFile = File(cameraDir, "capture_${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
        val uri = runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photoFile) }
            .getOrElse {
                photoFile.delete()
                Toast.makeText(ctx, "Unable to prepare camera capture: ${it.message}", Toast.LENGTH_LONG).show()
                dismissAllowingStateLoss()
                return
            }
        pendingCameraUri = uri
        pendingCameraFile = photoFile
        launchSafely(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = android.content.ClipData.newUri(ctx.contentResolver, "Oreshik photo", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            KIND_CAMERA,
        )
    }

    private fun clearPendingCamera() {
        pendingCameraUri = null
        pendingCameraFile = null
    }
}
