package com.pranav.voicedepth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.NormalizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthEngine(private val context: Context) {

    private var tfliteInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    /** True if the TFLite model loaded successfully */
    val isInitialized: Boolean get() = tfliteInterpreter != null
    /** Error message if initialization failed, null otherwise */
    var initError: String? = null
        private set
    
    // Performance: Pre-allocated buffers
    private val inputSize = 256
    private val outputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputArray = FloatArray(inputSize * inputSize)
    private val pixels = IntArray(inputSize * inputSize)
    private var depthBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    
    // Performance: Cache the stateless image processor
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(115.0f, 58.0f))
        .build()
    
    // EMA Smoothing State
    private var smoothedMin: Float? = null
    private var smoothedMax: Float? = null
    private val EMA_ALPHA = 0.1f // 10% new frame, 90% history

    init {
        setupTflite()
    }

    private fun setupTflite() {
        try {
            val model = FileUtil.loadMappedFile(context, "midas_small.tflite")
            val options = Interpreter.Options()
            
            try {
                // Attempt GPU initialization first
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                tfliteInterpreter = Interpreter(model, options)
                android.util.Log.i("DepthEngine", "Successfully initialized TFLite on GPU")
            } catch (e: Exception) {
                // GPU initialization failed (very common on budget devices or unsupported drivers)
                android.util.Log.w("DepthEngine", "GPU initialization failed, falling back to CPU", e)
                
                // Clean up any partially initialized delegate
                gpuDelegate?.close()
                gpuDelegate = null
                
                // Retry strict CPU initialization
                val cpuOptions = Interpreter.Options()
                val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                cpuOptions.setNumThreads(cpuThreads)
                tfliteInterpreter = Interpreter(model, cpuOptions)
                android.util.Log.i("DepthEngine", "Successfully initialized TFLite on CPU")
            }
        } catch (e: Exception) {
            initError = e.message ?: "Unknown TFLite initialization error"
            android.util.Log.e("DepthEngine", "CRITICAL: Failed to load or initialize TFLite model completely", e)
        }
    }

    // Caches for scaling to avoid GC churn
    private var cachedOutputBitmap: Bitmap? = null
    private var cachedCanvas: Canvas? = null
    private val scaledDestRect = Rect()

    fun processFrame(bitmap: Bitmap): Bitmap {
        return processAiDepth(bitmap)
    }

    private fun processAiDepth(bitmap: Bitmap): Bitmap {
        val interpreter = tfliteInterpreter
        if (interpreter == null) {
            // Return a black frame if TFLite fails to avoid confusing the user with a raw RGB feed
            return createErrorBitmap(bitmap.width, bitmap.height)
        }

        // Re-use the cached image processor (it's stateless and reusable)
        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // Run inference
        outputBuffer.rewind()
        interpreter.run(tensorImage.buffer, outputBuffer)

        // Post-process output
        outputBuffer.rewind()
        
        var frameMin = Float.MAX_VALUE
        var frameMax = Float.MIN_VALUE
        
        for (i in 0 until inputSize * inputSize) {
            val depth = outputBuffer.float
            outputArray[i] = depth
            if (depth < frameMin) frameMin = depth
            if (depth > frameMax) frameMax = depth
        }

        // Apply Exponential Moving Average (EMA) to smooth out jumping bounds
        smoothedMin = if (smoothedMin == null) frameMin else (EMA_ALPHA * frameMin) + ((1 - EMA_ALPHA) * smoothedMin!!)
        smoothedMax = if (smoothedMax == null) frameMax else (EMA_ALPHA * frameMax) + ((1 - EMA_ALPHA) * smoothedMax!!)

        val range = smoothedMax!! - smoothedMin!!
        for (i in 0 until inputSize * inputSize) {
            // Clamp normalized value between 0 and 255 to prevent overflow from smooth lag
            var normalizedFloat = if (range > 0) ((outputArray[i] - smoothedMin!!) / range * 255) else 0f
            if (normalizedFloat < 0f) normalizedFloat = 0f
            if (normalizedFloat > 255f) normalizedFloat = 255f
            
            val normalized = normalizedFloat.toInt()
            pixels[i] = Color.rgb(normalized, normalized, normalized)
        }
        
        depthBitmap.setPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        
        if (cachedOutputBitmap == null || cachedOutputBitmap!!.width != bitmap.width || cachedOutputBitmap!!.height != bitmap.height) {
            cachedOutputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            cachedCanvas = Canvas(cachedOutputBitmap!!)
            scaledDestRect.set(0, 0, bitmap.width, bitmap.height)
        }
        
        cachedCanvas?.drawBitmap(depthBitmap, null, scaledDestRect, null)
        return cachedOutputBitmap!!
    }

    private fun createErrorBitmap(width: Int, height: Int): Bitmap {
        if (cachedOutputBitmap == null || cachedOutputBitmap!!.width != width || cachedOutputBitmap!!.height != height) {
            cachedOutputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            cachedCanvas = Canvas(cachedOutputBitmap!!)
        }
        cachedCanvas?.drawColor(Color.BLACK)
        return cachedOutputBitmap!!
    }

    fun stop() {
        tfliteInterpreter?.close()
        gpuDelegate?.close()
    }
}
