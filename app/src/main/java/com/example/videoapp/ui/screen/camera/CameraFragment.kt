package com.example.videoapp.ui.screen.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.*
import androidx.fragment.app.Fragment
import com.example.videoapp.R
import com.example.videoapp.R.string
import com.example.videoapp.data.constants.Constants.FRAGMENT_DIALOG
import com.example.videoapp.data.constants.Constants.REQUEST_CAMERA_PERMISSION
import com.example.videoapp.ui.dialog.ErrorDialog
import kotlinx.android.synthetic.main.camera_fragment.*
import org.koin.android.viewmodel.ext.android.viewModel
import pub.devrel.easypermissions.EasyPermissions
import java.util.concurrent.Semaphore

class CameraFragment : Fragment(), EasyPermissions.PermissionCallbacks {
    companion object {
        const val BACKGROUND_THREAD = "CameraBackground"
        fun newInstance() = CameraFragment()
    }

    private val viewModel: CameraViewModel by viewModel()
    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var captureRequest: CaptureRequest? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewSize: Size? = null
    private var cameraId: String? = null
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = false

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    }
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@CameraFragment.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            activity?.finish()
        }
    }
    private var imageReader: ImageReader? = null
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
            val image = reader?.acquireNextImage()
            image?.let {
                val planes = it.planes
                val w = it.width
                val h = it.height
                var dstIndex = 0
                val yBytes = ByteArray(w * h)
                val uBytes = ByteArray(w * h / 4)
                val vBytes = ByteArray(w * h /4)
                var uIndex = 0
                var vIndex = 0
                var pixelsStride = 0
                var rowStride = 0
                for (i in 0 until planes.size) {
                    pixelsStride = planes[i].pixelStride
                    rowStride = planes[i].rowStride
                    val buffer = planes[i].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    var srcIndex = 0
                    when (i) {
                        0 -> {
                            for (j in 0 until h) {
                                System.arraycopy(bytes, srcIndex, yBytes, dstIndex, w)
                                srcIndex += rowStride
                                dstIndex += w
                            }
                        }
                        1 -> {
                            for (j in 0 until h / 2) {
                                for (k in 0 until w / 2) {
                                    uBytes[uIndex++] = bytes[srcIndex]
                                    srcIndex += pixelsStride
                                }
                                if (pixelsStride == 2) {
                                    srcIndex += rowStride - w
                                } else if (pixelsStride == 1) {
                                    srcIndex += rowStride - w / 2
                                }
                            }
                        }
                        2 -> {
                            for (j in 0 until h / 2) {
                                for (k in 0 until w / 2) {
                                    vBytes[vIndex++] = bytes[srcIndex]
                                    srcIndex += pixelsStride
                                }
                                if (pixelsStride == 2) {
                                    srcIndex += rowStride - w
                                } else if (pixelsStride == 1) {
                                    srcIndex += rowStride - w / 2
                                }
                            }
                        }
                    }
                }
                it.close()
            }
        }
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.camera_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureCamera.isAvailable) {
            openCamera()
        } else {
            textureCamera.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onStop() {
        super.onStop()
        closeCamera()
        imageReader?.close()
        imageReader = null
        closeBackgroundThread()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!EasyPermissions.hasPermissions(requireContext(), Manifest.permission.CAMERA)) {
            EasyPermissions.requestPermissions(
                this,
                getString(string.camera_permission),
                REQUEST_CAMERA_PERMISSION,
                Manifest.permission.CAMERA
            )
            return
        }
        setupCamera()
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun setupCamera() {
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }
                val map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: continue
                this.previewSize = map.getOutputSizes(SurfaceTexture::class.java)[0]
                this.cameraId = cameraId
                imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 1)
                imageReader?.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
                return
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(com.example.videoapp.R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        }

    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread(BACKGROUND_THREAD).also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    private fun createCameraPreviewSession() {
        try {
            val surfaceTexture = textureCamera.surfaceTexture
            previewSize?.let {
                surfaceTexture.setDefaultBufferSize(it.width, it.height)
            }
            val previewSurface = Surface(surfaceTexture)
            val imageSurface = imageReader?.surface
            captureRequestBuilder =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(previewSurface)
            captureRequestBuilder?.addTarget(imageSurface)
            cameraDevice?.createCaptureSession(
                arrayListOf(previewSurface, imageSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                    }

                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            captureRequest = captureRequestBuilder?.build()
                            cameraCaptureSession = session.apply {
                                setRepeatingRequest(
                                    captureRequest,
                                    captureCallback,
                                    backgroundHandler
                                )
                            }
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closeCamera() {
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun closeBackgroundThread() {
        backgroundHandler?.let {
            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        }
    }

    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        activity?.onBackPressed()
    }

    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        openCamera()
    }
}
