package com.fishtechy.multiframevideowritter

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.fishtechy.multiframevideowritter.videoconfig.VideoConfig
import java.io.File
import java.nio.ByteBuffer

class MultiframeVideoWriter(
    private val context: Context,
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val  videoConfig: VideoConfig,
    private val fps: Int = 30,
) {
    private val TAG = "MultiFrameVideoWriter"

    private val tempDir = File(context.cacheDir, "temp_frames")

    private val encoder: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var encoderStarted = false
    private var hasWrittenFrame = false // NEW

    private var frameCount = 0L
    var currentFrameCount = 0

    init {
        Log.d(TAG, "Initializing encoder and muxer...")

        try {
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, width * height)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType("video/avc")
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.d(TAG, "Encoder configured: width=$width, height=$height, fps=$fps")

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Log.d(TAG, "Muxer initialized at path=${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing encoder or muxer: ${e.message}", e)
            throw e
        }
    }

    /** Save frame to temp file */
    fun saveFrameByteArray(byte: ByteArray) {
        try {
            tempDir.apply { if (!exists()) mkdirs() }
            Log.d(TAG, "Saving frame #$currentFrameCount (${byte.size} bytes) to temp file...")
            val tempFile = saveNV12ToTempFile(byte)
            currentFrameCount++
            Log.d(TAG, "Frame #${currentFrameCount - 1} saved to ${tempFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save frame: ${e.message}", e)
        }
    }

    fun writeNeighboringFrames(centerFrame: Int) {
        if (currentFrameCount <= 0) {
            Log.w(TAG, "No frames available to write")
            return
        }

        val minFrame = (centerFrame - videoConfig.neighboringWindowLimit).coerceAtLeast(0)
        val maxFrame = (centerFrame + videoConfig.neighboringWindowLimit).coerceAtMost(currentFrameCount - 1)

        Log.d(TAG, "Writing neighboring frames: $minFrame -> $maxFrame (center=$centerFrame)")

        for (frameIndex in minFrame..maxFrame) {
            if (frameCount > videoConfig.totalFrames) return

            val nv12File = File(tempDir, "frame_$frameIndex.nv12")
            if (!nv12File.exists()) {
                Log.w(TAG, "Frame #$frameIndex file does not exist, skipping")
                continue
            }

            try {
                writeFrameNV12(frameIndex)
                Log.d(TAG, "Successfully wrote frame #$frameIndex -> ${nv12File.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write frame #$frameIndex: ${e.message}", e)
            }
        }
    }

    /** Write frame to encoder and muxer */
    fun writeFrameNV12(frameNumber: Int) {
        val nv12File =  File(tempDir, "frame_$frameNumber.nv12")
        if (!nv12File.exists()) return

        val (nv12, width, height) = readNV12FromTempFile(nv12File)
        val expectedSize = width * height * 3 / 2
        if (nv12.size != expectedSize) {
            Log.e(
                TAG,
                "Frame #$frameNumber size mismatch: got ${nv12.size} bytes, " +
                        "expected $expectedSize (${width}x${height} NV12), skipping"
            )
            nv12File.delete()
            return
        }
        val pts = frameCount * 1_000_000L / fps
        frameCount++

        try {
            if (!encoderStarted) {
                encoder.start()
                encoderStarted = true
                Log.i(TAG, "Encoder started, inputFormat=${encoder.inputFormat}")
            }

            hasWrittenFrame = true // NEW

            val inputIndex = encoder.dequeueInputBuffer(-1)
            if (inputIndex >= 0) {
                // COLOR_FormatYUV420Flexible means the codec chooses the input
                // layout (stride, slice height, planar vs semi-planar), and
                // hardware encoders often require row strides wider than the
                // frame (e.g. 1080 -> 1088/1152). Raw put() of tight-packed
                // NV12 then shears the picture and leaves the plane tail empty
                // (green band), so copy through the input Image's plane layout.
                val inputImage = encoder.getInputImage(inputIndex)
                if (inputImage != null) {
                    if (frameCount == 1L) {
                        val y = inputImage.planes[0]
                        val u = inputImage.planes[1]
                        Log.i(
                            TAG,
                            "Codec input layout: yRowStride=${y.rowStride} yPixelStride=${y.pixelStride} " +
                                    "uvRowStride=${u.rowStride} uvPixelStride=${u.pixelStride} (frame width=$width)"
                        )
                    }
                    fillInputImageFromNv12(inputImage, nv12, width, height)
                } else {
                    Log.w(TAG, "getInputImage returned null, falling back to raw put()")
                    val inputBuffer = encoder.getInputBuffer(inputIndex) ?: return
                    inputBuffer.clear()
                    inputBuffer.put(nv12)
                }
                encoder.queueInputBuffer(inputIndex, 0, expectedSize, pts, 0)
            }

            drainEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing frame #$frameNumber: ${e.message}", e)
        } finally {
            nv12File.delete()
        }
    }

    /** Copies a tight-packed NV12 frame into the codec's flexible input Image,
     *  honoring each plane's rowStride/pixelStride. */
    private fun fillInputImageFromNv12(image: Image, nv12: ByteArray, width: Int, height: Int) {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        if (yPlane.pixelStride == 1) {
            for (row in 0 until height) {
                yBuffer.position(row * yPlane.rowStride)
                yBuffer.put(nv12, row * width, width)
            }
        } else {
            for (row in 0 until height) {
                for (col in 0 until width) {
                    yBuffer.put(row * yPlane.rowStride + col * yPlane.pixelStride, nv12[row * width + col])
                }
            }
        }

        // NV12 source chroma: rows of interleaved pairs (u0 v0 u1 v1 ...)
        val uvOffset = width * height
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        for (row in 0 until height / 2) {
            val srcRow = uvOffset + row * width
            for (col in 0 until width / 2) {
                uPlane.buffer.put(row * uPlane.rowStride + col * uPlane.pixelStride, nv12[srcRow + col * 2])
                vPlane.buffer.put(row * vPlane.rowStride + col * vPlane.pixelStride, nv12[srcRow + col * 2 + 1])
            }
        }
    }

    /** Drain encoder and write output to muxer */
    private fun drainEncoder(endOfStream: Boolean = false) {
        if (!encoderStarted) return

        if (endOfStream && hasWrittenFrame) {
            val inputIndex = encoder.dequeueInputBuffer(-1)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    frameCount * 1_000_000L / fps,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)

        while (outputIndex >= 0) {
            val encodedBuffer = encoder.getOutputBuffer(outputIndex)

            if (!muxerStarted) {
                trackIndex = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                muxerStarted = true
            }

            encodedBuffer?.let { muxer.writeSampleData(trackIndex, it, bufferInfo) }
            encoder.releaseOutputBuffer(outputIndex, false)
            outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    /** Finish writing video safely */
    fun finish() {
        Log.d(TAG, "Finalizing video writing...")

        try {
            if (encoderStarted && hasWrittenFrame) drainEncoder(endOfStream = true)

            if (encoderStarted) {
                encoder.stop()
                encoder.release()
                encoderStarted = false
            }

            if (muxerStarted) {
                muxer.stop()
                muxer.release()
                muxerStarted = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error finishing video: ${e.message}", e)
        } finally {
            if (!hasWrittenFrame) outputFile.delete()
            frameCount = 0L
            tempDir.deleteRecursively()
        }

        Log.d(TAG, "Video writing completed successfully: ${outputFile.absolutePath}")
    }

    /** Save NV12 to temp file with metadata */
    private fun saveNV12ToTempFile(nv12: ByteArray): File {
        val tempFile = File(tempDir, "frame_$currentFrameCount.nv12")
        tempFile.outputStream().use { fos ->
            val buffer = ByteBuffer.allocate(8)
            buffer.putInt(width)
            buffer.putInt(height)
            fos.write(buffer.array())
            fos.write(nv12)
        }
        return tempFile
    }

    /** Read NV12 + metadata from temp file */
    private fun readNV12FromTempFile(file: File): Triple<ByteArray, Int, Int> {
        val fis = file.inputStream()
        val header = ByteArray(8)
        fis.read(header)
        val buffer = ByteBuffer.wrap(header)
        val width = buffer.int
        val height = buffer.int
        val nv12 = fis.readBytes()
        fis.close()
        return Triple(nv12, width, height)
    }

    /** Save video to gallery */
    fun saveVideoToGallery(context: Context, sourceFile: File, fileName: String) {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val videoUri = resolver.insert(collection, contentValues)

            if (videoUri != null) {
                resolver.openOutputStream(videoUri).use { outStream ->
                    sourceFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream!!)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(videoUri, contentValues, null, null)
                Toast.makeText(context, "Video saved to gallery", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to save video", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error saving video: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
