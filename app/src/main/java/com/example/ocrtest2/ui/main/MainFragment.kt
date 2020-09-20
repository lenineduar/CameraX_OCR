package com.example.ocrtest2.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.ocrtest2.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.android.synthetic.main.main_fragment.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit

class MainFragment : Fragment() {

    companion object {
        fun newInstance() = MainFragment()
        private const val TAG = "MainFragment"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS  = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewModel: MainViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Request camera permissions
        if (!allPermissionsGranted()) {
            requireActivity().requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

    }

    override fun onResume() {
        super.onResume()

        // Set up the listener for take photo button
        camera_capture_button.setOnClickListener { takePhoto() }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?): View = inflater.inflate(R.layout.main_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewView.post {
            startCamera()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg")
        Log.d(TAG, "takePhoto: ${photoFile.absolutePath}")
        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        /*imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(requireContext()), object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    Log.d(TAG, msg)
                }
            })*/
        imageCapture.takePicture(cameraExecutor,object: ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                Log.d(TAG, "onCaptureSuccess: entro al callback de manera exitosa")
                Log.d(TAG, "onCaptureSuccess: from image, grados  ${image.imageInfo.rotationDegrees} width ${image.width} height ${image.height}")
                var bmp = image.toBitmap()
                val matrix = Matrix()

                matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())

                val scaledBitmap = Bitmap.createScaledBitmap(
                    bmp,
                    bmp.width,
                    bmp.height,
                    true)

                val rotatedBitmap = Bitmap.createBitmap(
                    scaledBitmap,
                    0,
                    0,
                    scaledBitmap.width,
                    scaledBitmap.height,
                    matrix,
                    true
                )
                image.close()
                requireActivity().runOnUiThread {
                    takenImage.setImageBitmap(rotatedBitmap)
                    Log.d(TAG, "onCaptureSuccess: se coloco la foto en el imageView")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.d(TAG, "onCaptureSuccess: entro al callback pero fallo")
            }
        })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        val rotation = previewView.display.rotation
        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Preview
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build().apply {
                    setSurfaceProvider(previewView.createSurfaceProvider())
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()


            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalyzer
                )

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
        val recognizer by lazy {TextRecognition.getClient()}
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        @SuppressLint("UnsafeExperimentalUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            /*// If there are no listeners attached, we don't need to perform analysis

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image
            val luma = pixels.average()

            // Call all listeners with new value
            listener(luma)

            image.close()*/

            /*--------------------------------------------------------------*/
            //Log.d(TAG, "analyze: se llama al metodo")
            val mediaImage = imageProxy.image
            Log.d(TAG, "analyze: mediaImage value $mediaImage")
            mediaImage?.let {
                val image = InputImage.fromMediaImage(it, imageProxy.imageInfo.rotationDegrees)
                // Pass image to an ML Kit Vision API
                // ...
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if(!visionText.text?.isEmpty())
                            Log.d(TAG, "analyze: texto reconocido: \n ${visionText.text}")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "analyze: fallo el reconocimiento",e)
                    }
                    .addOnCompleteListener {
                        mediaImage.close()
                        imageProxy.close()
                        Log.d(TAG, "analyze: addOnCompleteListener called")
                    }
            }
        }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = requireActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else requireActivity().filesDir
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!allPermissionsGranted()) {
                Toast.makeText(requireContext(),
                    "Permissions not granted by the user.",
                    Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
    }

    private fun ImageProxy.toByteArray(): ByteArray {
        val buffer =  planes[0].buffer
        buffer.rewind()    // Rewind the buffer to zero
        val data = ByteArray(buffer.remaining())
        buffer.get(data)   // Copy the buffer into a byte array
        return data // Return the byte array
    }

    private fun ImageProxy.toBitmap(): Bitmap{
        val byteArray = toByteArray()
        return BitmapFactory.decodeByteArray(byteArray,0,byteArray.size)
    }
}