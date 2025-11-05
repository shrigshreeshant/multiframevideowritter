package com.fishtechy.multiframevideowritter

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer

class MultiframeVideoWriter(
    private val context: Context,
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
) {
    private val TAG = "MultiFrameVideoWriter"

    private val tempDir = File(context.cacheDir, "temp_frames")

    private val encoder: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var encoderStarted = false

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
            tempDir.apply {
                if (!exists()){
                    mkdirs()
                }
            }
            Log.d(TAG, "Saving frame #$currentFrameCount (${byte.size} bytes) to temp file...")
            val tempFile = saveNV12ToTempFile(byte)
            currentFrameCount++
            Log.d(TAG, "Frame #${currentFrameCount - 1} saved to ${tempFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save frame: ${e.message}", e)
        }
    }


    fun writeNeighboringFrames(centerFrame: Int, neighborRange: Int = 5 ){
        // Ensure there are frames to process
        if (currentFrameCount <= 0) {
            Log.w(TAG, "No frames available to write")
            return
        }

        // Calculate safe min/max frame indices
        val minFrame = (centerFrame - neighborRange).coerceAtLeast(0)
        val maxFrame = (centerFrame + neighborRange).coerceAtMost(currentFrameCount - 1)

        Log.d(TAG, "Writing neighboring frames: $minFrame -> $maxFrame (center=$centerFrame)")

        for (frameIndex in minFrame..maxFrame) {
            // Create the expected file path for this frame
            val nv12File = File(tempDir, "frame_$frameIndex.nv12")

            // Check if the file exists
            if (!nv12File.exists()) {
                Log.w(TAG, "Frame #$frameIndex file does not exist, skipping")
                continue
            }

            try {
                // Call your existing function to write the frame
                writeFrameNV12(frameIndex)
                Log.d(TAG, "Successfully wrote frame #$frameIndex -> ${nv12File.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write frame #$frameIndex: ${e.message}", e)
            }
        }
    }

    /** Write frame to encoder and muxer */
    fun writeFrameNV12(frameNumber: Int) {
        Log.d(TAG, "Attempting to write frame #$frameNumber")

        val nv12File =  File(tempDir, "frame_$frameNumber.nv12")

        if (!nv12File.exists()) {
            Log.e(TAG, "Frame #$frameNumber not found in temp files")
            return
        }

        val (nv12, width, height) = readNV12FromTempFile(nv12File)
        val pts = frameCount * 1_000_000L / fps
        frameCount++

        try {
            if (!encoderStarted) {
                encoder.start()
                encoderStarted = true
                Log.d(TAG, "Encoder started")
            }

            val inputIndex = encoder.dequeueInputBuffer(-1)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex)
                if (inputBuffer == null) {
                    Log.e(TAG, "Input buffer is null for frame #$frameNumber")
                    return
                }

                inputBuffer.clear()
                inputBuffer.put(nv12)
                val size = width * height * 3 / 2
                encoder.queueInputBuffer(inputIndex, 0, size, pts, 0)
                Log.d(TAG, "Queued frame #$frameNumber (size=$size, pts=$pts)")
            } else {
                Log.w(TAG, "No input buffer available for frame #$frameNumber")
            }

            drainEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing frame #$frameNumber: ${e.message}", e)
        } finally {
            // Delete temp file
            val deleted = nv12File.delete()
            Log.d(TAG, "Frame #$frameNumber temp file deleted: $deleted")
        }
    }

    /** Drain encoder and write output to muxer */
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)

        while (outputIndex >= 0) {
            val encodedBuffer = encoder.getOutputBuffer(outputIndex)

            if (!muxerStarted) {
                val format = encoder.outputFormat
                trackIndex = muxer.addTrack(format)
                muxer.start()
                muxerStarted = true
                Log.d(TAG, "Muxer started with track index $trackIndex")
            }

            encodedBuffer?.let {
                muxer.writeSampleData(trackIndex, it, bufferInfo)
                Log.d(
                    TAG,
                    "Wrote sample to muxer (size=${bufferInfo.size}, pts=${bufferInfo.presentationTimeUs})"
                )
            }

            encoder.releaseOutputBuffer(outputIndex, false)
            outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    /** Finish writing video */
    fun finish() {
        Log.d(TAG, "Finalizing video writing...")

        try {
            drainEncoder()

            if (encoderStarted) {
                encoder.stop()
                encoder.release()
                encoderStarted = false
                Log.d(TAG, "Encoder stopped and released")
            }

            if (muxerStarted) {
                muxer.stop()
                muxer.release()
                muxerStarted = false
                Log.d(TAG, "Muxer stopped and released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing video: ${e.message}", e)
        } finally {
            if(frameCount==0L){

                outputFile.delete()
            }else{
                frameCount=0L
            }
            // Clean up remaining temp files

            tempDir.deleteRecursively()
            Log.d(TAG, "Temporary files cleaned up")
        }

        Log.d(TAG, "Video writing completed successfully: ${outputFile.absolutePath}")
    }

    /** Save NV12 to temp file with metadata */
    private fun saveNV12ToTempFile(nv12: ByteArray): File {
        val tempFile = File(tempDir, "frame_$currentFrameCount.nv12")
        try {
            tempFile.outputStream().use { fos ->
                val buffer = ByteBuffer.allocate(8)
                buffer.putInt(width)
                buffer.putInt(height)
                fos.write(buffer.array())
                fos.write(nv12)
            }
            Log.d(TAG, "Saved NV12 frame to ${tempFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving NV12 temp file: ${e.message}", e)
        }
        return tempFile
    }

    /** Read NV12 + metadata from temp file */
    private fun readNV12FromTempFile(file: File): Triple<ByteArray, Int, Int> {
        Log.d(TAG, "Reading NV12 frame from ${file.absolutePath}")
        try {
            val fis = file.inputStream()
            val header = ByteArray(8)
            fis.read(header)
            val buffer = ByteBuffer.wrap(header)
            val width = buffer.int
            val height = buffer.int
            val nv12 = fis.readBytes()
            fis.close()
            Log.d(TAG, "Read NV12 frame (width=$width, height=$height, size=${nv12.size})")
            return Triple(nv12, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading NV12 temp file: ${e.message}", e)
            throw e
        }
    }

    fun saveVideoToGallery(context: Context, sourceFile: File, fileName: String) {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                // Save to Movies folder in gallery
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

                // Mark the video as finished
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
