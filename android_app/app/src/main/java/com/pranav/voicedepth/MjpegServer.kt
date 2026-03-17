package com.pranav.voicedepth

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException

class MjpegServer(port: Int) : NanoHTTPD("127.0.0.1", port) {

    private val frameLock = Object()
    private var currentFrame: ByteArray? = null

    fun setFrame(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        synchronized(frameLock) {
            currentFrame = stream.toByteArray()
            frameLock.notifyAll()
        }
    }

    private var connectionCount = 0
    private val MAX_CONNECTIONS = 5

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        android.util.Log.d("MjpegServer", "Request: ${session.method} $uri from ${session.remoteIpAddress}")
        
        return if (uri == "/depth_stream.mjpeg") {
            synchronized(this) {
                if (connectionCount >= MAX_CONNECTIONS) {
                    android.util.Log.w("MjpegServer", "Rejecting connection: MAX_CONNECTIONS reached ($MAX_CONNECTIONS)")
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Maximum connections reached")
                }
                connectionCount++
            }
            android.util.Log.i("MjpegServer", "New MJPEG connection. Total: $connectionCount")
            createMjpegResponse()
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun createMjpegResponse(): Response {
        val boundary = "frame_boundary"
        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace;boundary=$boundary", MjpegStream())
    }

    private inner class MjpegStream : java.io.InputStream() {
        private var currentBuffer: ByteArray? = null
        private var bufferPos = 0
        private var lastSentFrame: ByteArray? = null

        override fun read(): Int {
            val singleByte = ByteArray(1)
            val n = read(singleByte, 0, 1)
            return if (n <= 0) -1 else singleByte[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            
            while (currentBuffer == null || bufferPos >= currentBuffer!!.size) {
                val frame: ByteArray?
                synchronized(frameLock) {
                    while (currentFrame == null || currentFrame === lastSentFrame) {
                        try { 
                            frameLock.wait() 
                        } catch (e: InterruptedException) { 
                            return -1 // Interrupted, close stream
                        }
                    }
                    frame = currentFrame
                    lastSentFrame = currentFrame
                }
                
                if (frame == null) continue // Should not happen given the wait structure, but safe check
                
                val frameSize = frame.size
                // Standard MJPEG headers
                val header = ("--frame_boundary\r\n" +
                             "Content-Type: image/jpeg\r\n" +
                             "Content-Length: $frameSize\r\n\r\n").toByteArray()
                val footer = "\r\n".toByteArray()
                
                val fullFrame = ByteArray(header.size + frameSize + footer.size)
                System.arraycopy(header, 0, fullFrame, 0, header.size)
                System.arraycopy(frame, 0, fullFrame, header.size, frameSize)
                System.arraycopy(footer, 0, fullFrame, header.size + frameSize, footer.size)
                
                currentBuffer = fullFrame
                bufferPos = 0
            }

            val available = currentBuffer!!.size - bufferPos
            val bytesToRead = minOf(len, available)
            System.arraycopy(currentBuffer!!, bufferPos, b, off, bytesToRead)
            bufferPos += bytesToRead
            return bytesToRead
        }

        override fun close() {
            super.close()
            synchronized(this@MjpegServer) {
                if (connectionCount > 0) connectionCount--
            }
        }
    }
}
