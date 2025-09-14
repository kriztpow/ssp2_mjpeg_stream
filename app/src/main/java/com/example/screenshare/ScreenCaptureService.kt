package com.example.screenshare

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.net.NetworkInterface
import java.util.Collections

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var webServer: WebServer? = null
    @Volatile private var latestJpeg: ByteArray? = null
    private var width = 0
    private var imageHandlerThread: android.os.HandlerThread? = null
    private var imageHandler: android.os.Handler? = null
    private var height = 0
    private var dpi = 0

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", -1)
            // ✅ Forma moderna y compatible
            val data = intent.getParcelableExtra("data", Intent::class.java)

            if (resultCode != -1 && data != null) {
                Log.d(TAG, "Starting projection with resultCode: $resultCode")
                startProjection(resultCode, data)
            } else {
                Log.e(TAG, "Invalid resultCode or data")
                stopSelf()
            }
        } else {
            Log.e(TAG, "Intent is null")
            stopSelf()
        }

        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val metrics: DisplayMetrics = resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels
        dpi = metrics.densityDpi

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

                imageHandlerThread = android.os.HandlerThread("ImageListener")
        imageHandlerThread?.start()
        imageHandler = android.os.Handler(imageHandlerThread!!.looper)
imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        imageReader?.setOnImageAvailableListener({ reader ->
            var image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap = android.graphics.Bitmap.createBitmap(width + rowPadding / pixelStride, height, android.graphics.Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    val baos = java.io.ByteArrayOutputStream()
                    cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                    latestJpeg = baos.toByteArray()
                    baos.close()
                    bitmap.recycle()
                    cropped.recycle()
                } catch (ex: Exception) {
                    Log.e(TAG, "Error processing image", ex)
                } finally {
                    image.close()
                }
            }
        }, imageHandler)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // Iniciar servidor web
        webServer = WebServer(8080)
        webServer?.start()
        val ip = getDeviceIpAddress()
        Log.d(TAG, "🌐 Web server should be reachable at: http://$ip:8080")

        Log.d(TAG, "Projection started and web server running on port 8080")
    }

    override fun onDestroy() {
        imageHandlerThread?.quitSafely()
        imageHandlerThread = null
        imageHandler = null
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        webServer?.stop()
        Log.d(TAG, "Projection stopped and resources released")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Screen Capture Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Screen Sharing Active")
            .setContentText("Your screen is being shared")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
    }

    // --- Servidor HTTP interno ---
    inner 
class WebServer(port: Int) : NanoHTTPD("0.0.0.0", port) {
        override fun serve(session: IHTTPSession?): Response {
            try {
                val uri = session?.uri ?: "/"
                return when {
                    uri.startsWith("/screenshot") -> {
                        val bytes = latestJpeg
                        if (bytes != null) {
                            newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
                        } else {
                            newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "No image captured yet.")
                        }
                    }
                    uri.startsWith("/stream.mjpg") -> {
                        return newChunkedResponse(
                            Response.Status.OK,
                            "multipart/x-mixed-replace; boundary=frame",
                            object : java.io.InputStream() {
                                override fun read(): Int {
                                    return -1 // not used
                                }

                                override fun transferTo(out: java.io.OutputStream): Long {
                                    val boundary = "--frame
".toByteArray()
                                    try {
                                        while (true) {
                                            val frame = latestJpeg
                                            if (frame != null) {
                                                out.write(boundary)
                                                out.write("Content-Type: image/jpeg
".toByteArray())
                                                out.write("Content-Length: ${frame.size}

".toByteArray())
                                                out.write(frame)
                                                out.write("
".toByteArray())
                                                out.flush()
                                            }
                                            Thread.sleep(50)
                                        }
                                    } catch (e: Exception) {
                                        Log.d(TAG, "Stream client disconnected: ${e.message}")
                                    }
                                    return 0
                                }
                            }
                        )
                    }
                    else -> {
                        val ip = getDeviceIpAddress()
                        val msg = "✅ Screen capture server is running at http://$ip:$listeningPort
" +
                                  "Available endpoints:
" +
                                  "/ -> this message
" +
                                  "/screenshot.jpg -> latest captured frame (jpeg)
" +
                                  "/stream.mjpg -> MJPEG live stream
"
                        newFixedLengthResponse(msg)
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error in WebServer.serve", ex)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error: ${ex.message}")
            }
        } else {
                            newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "No image captured yet.")
                        }
                    }
                    uri.startsWith("/stream.mjpg") -> {
                        return object : Response(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", null, 0) {
                            override fun send(outputStream: java.io.OutputStream, p1: Long) {
                                val boundary = "--frame
"
                                try {
                                    val out = java.io.BufferedOutputStream(outputStream)
                                    while (true) {
                                        val frame = latestJpeg
                                        if (frame != null) {
                                            out.write(boundary.toByteArray())
                                            out.write("Content-Type: image/jpeg
".toByteArray())
                                            out.write("Content-Length: ${frame.size}

".toByteArray())
                                            out.write(frame)
                                            out.write("
".toByteArray())
                                            out.flush()
                                        }
                                        Thread.sleep(50) // ~20 fps
                                    }
                                } catch (e: Exception) {
                                    Log.d(TAG, "Stream client disconnected")
                                }
                            }
                        }
                    }
                    else -> {
                        val ip = getDeviceIpAddress()
                        val msg = "✅ Screen capture server is running at http://$ip:$listeningPort
" +
                                  "Available endpoints:
" +
                                  "/ -> this message
" +
                                  "/screenshot.jpg -> latest captured frame (jpeg)
" +
                                  "/stream.mjpg -> MJPEG live stream
"
                        newFixedLengthResponse(msg)
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error in WebServer.serve", ex)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error: ${ex.message}")
            }
        } else {
                        newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "No image captured yet.")
                    }
                } else {
                    val ip = getDeviceIpAddress()
                    val msg = "✅ Screen capture server is running at http://$ip:$listeningPort\n" +
                              "Available endpoints:\n" +
                              "/ -> this message\n" +
                              "/screenshot.jpg -> latest captured frame (jpeg)\n"
                    return newFixedLengthResponse(msg)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error in WebServer.serve", ex)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error: ${ex.message}")
            }
        }
    }


    // --- Obtener la IP de la red WiFi ---
    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP address", ex)
        }
        return "127.0.0.1"
    }
}
