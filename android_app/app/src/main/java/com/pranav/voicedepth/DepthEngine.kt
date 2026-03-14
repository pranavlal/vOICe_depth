package com.pranav.voicedepth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthEngine(private val context: Context) {

    private var arSession: Session? = null
    private var tfliteInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isArSupported = false
    
    // Performance: Pre-allocated buffers
    private val inputSize = 256
    private val outputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputArray = FloatArray(inputSize * inputSize)
    private val pixels = IntArray(inputSize * inputSize)
    private var depthBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)

    init {
        try {
            arSession = Session(context)
            val config = Config(arSession)
            if (arSession!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
                isArSupported = true
            }
            arSession!!.configure(config)
        } catch (e: Exception) {
            isArSupported = false
        }

        if (!isArSupported) {
            setupTflite()
        }
    }

    private fun setupTflite() {
        try {
            val model = FileUtil.loadMappedFile(context, "midas_small.tflite")
            val options = Interpreter.Options()
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
            } catch (e: Exception) {
                // Fallback to CPU if GPU not available
            }
            tfliteInterpreter = Interpreter(model, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Caches for scaling to avoid GC churn
    private var cachedOutputBitmap: Bitmap? = null
    private var cachedCanvas: Canvas? = null
    private val scaledDestRect = Rect()

    fun processFrame(bitmap: Bitmap): Bitmap {
        return if (isArSupported) {
            // Simplified: In a real app, we'd use ARCore's depth map from the acquired frame
            processAiDepth(bitmap)
        } else {
            processAiDepth(bitmap)
        }
    }

    private fun processAiDepth(bitmap: Bitmap): Bitmap {
        val interpreter = tfliteInterpreter ?: return bitmap

        // Pre-process image
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // Run inference
        outputBuffer.rewind()
        interpreter.run(tensorImage.buffer, outputBuffer)

        // Post-process output
        outputBuffer.rewind()
        
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        
        for (i in 0 until inputSize * inputSize) {
            val depth = outputBuffer.float
            outputArray[i] = depth
            if (depth < min) min = depth
            if (depth > max) max = depth
        }

        val range = max - min
        for (i in 0 until inputSize * inputSize) {
            val normalized = if (range > 0) ((outputArray[i] - min) / range * 255).toInt() else 0
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

    fun stop() {
        arSession?.close()
        tfliteInterpreter?.close()
        gpuDelegate?.close()
    }
}
