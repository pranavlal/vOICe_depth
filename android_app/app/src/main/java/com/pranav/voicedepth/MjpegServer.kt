package com.pranav.voicedepth

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream

class MjpegServer(hostname: String?, port: Int) : NanoHTTPD(hostname, port) {

    private val frameLock = Object()
    private var currentFrame: ByteArray? = null
    private val compressStream = ByteArrayOutputStream()
    @Volatile private var isRunning = true

    fun setFrame(bitmap: Bitmap) {
        compressStream.reset()
        // Compression happens on the camera thread (DepthStreamService handler)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, compressStream)
        synchronized(frameLock) {
            currentFrame = compressStream.toByteArray()
            frameLock.notifyAll()
        }
    }

    fun notifyShutdown() {
        isRunning = false
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
    }

    private var connectionCount = 0
    private val MAX_CONNECTIONS = 5

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val remoteIp = session.remoteIpAddress
        android.util.Log.i("MjpegServer", "Request: ${session.method} $uri from $remoteIp")
        
        return if (uri == "/depth_stream.mjpeg") {
            synchronized(this) {
                if (connectionCount >= MAX_CONNECTIONS) {
                    android.util.Log.w("MjpegServer", "Rejecting connection from $remoteIp: MAX_CONNECTIONS reached ($MAX_CONNECTIONS)")
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Maximum connections reached")
                }
                connectionCount++
            }
            android.util.Log.i("MjpegServer", "Accepted MJPEG connection from $remoteIp. Total: $connectionCount")
            createMjpegResponse()
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun createMjpegResponse(): Response {
        val boundary = "frame_boundary"
        // Use -1 for length to indicate chunked/undefined stream to NanoHTTPD
        return newFixedLengthResponse(Response.Status.OK, "multipart/x-mixed-replace;boundary=$boundary", MjpegStream(), -1)
    }

    private enum class StreamState { IDLE, HEADER, DATA, FOOTER }

    private inner class MjpegStream : java.io.InputStream() {
        private var lastSentFrame: ByteArray? = null
        
        // State machine to serve header, data, and footer without intermediate full-buffer copying
        private var state = StreamState.IDLE
        private var currentBuffer: ByteArray? = null
        private var bufferPos = 0

        override fun read(): Int {
            val singleByte = ByteArray(1)
            val n = read(singleByte, 0, 1)
            return if (n <= 0) -1 else singleByte[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            
            if (state == StreamState.IDLE) {
                // Wait for a new frame
                val frame: ByteArray?
                synchronized(frameLock) {
                    while (isRunning && (currentFrame == null || currentFrame === lastSentFrame)) {
                        try { 
                            frameLock.wait(5000) 
                        } catch (e: InterruptedException) { 
                            return -1 
                        }
                    }
                    if (!isRunning) return -1
                    frame = currentFrame
                }
                
                if (frame == null) return 0
                
                lastSentFrame = frame
                val header = ("--frame_boundary\r\n" +
                             "Content-Type: image/jpeg\r\n" +
                             "Content-Length: ${frame.size}\r\n\r\n").toByteArray()
                
                currentBuffer = header
                bufferPos = 0
                state = StreamState.HEADER
            }

            // Provide data from the current active buffer
            val buffer = currentBuffer ?: return -1
            val bytesToRead = minOf(len, buffer.size - bufferPos)
            System.arraycopy(buffer, bufferPos, b, off, bytesToRead)
            bufferPos += bytesToRead

            if (bufferPos >= buffer.size) {
                // Transition to next state
                when (state) {
                    StreamState.HEADER -> {
                        currentBuffer = lastSentFrame
                        bufferPos = 0
                        state = StreamState.DATA
                    }
                    StreamState.DATA -> {
                        currentBuffer = "\r\n".toByteArray()
                        bufferPos = 0
                        state = StreamState.FOOTER
                    }
                    StreamState.FOOTER -> {
                        state = StreamState.IDLE
                        currentBuffer = null
                        bufferPos = 0
                    }
                    else -> {}
                }
            }
            
            return bytesToRead
        }

        override fun close() {
            super.close()
            synchronized(this@MjpegServer) {
                if (connectionCount > 0) {
                    connectionCount--
                    android.util.Log.i("MjpegServer", "MJPEG connection closed. Total: $connectionCount")
                }
            }
        }
    }
}
