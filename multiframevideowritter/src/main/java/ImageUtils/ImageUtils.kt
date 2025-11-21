package ImageUtils




import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import io.github.crow_misia.libyuv.Nv12Buffer
import io.github.crow_misia.libyuv.Nv21Buffer
import io.github.crow_misia.libyuv.RotateMode
import io.github.crow_misia.libyuv.ext.ImageExt.toNv12Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageUtils {





    fun cropCpuImageToMatchArViewAspect(
        rgb: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        quality: Int = 95
    ): ByteArray? {
        return try {

            val yuvImage = YuvImage(rgb, ImageFormat.NV21, imageWidth, imageHeight, null)

            // Compute target aspect ratio from AR view
            val viewAspect = viewWidth.toFloat() / viewHeight
            val imageAspect = imageWidth.toFloat() / imageHeight

            val cropWidth: Int
            val cropHeight: Int

            if (imageAspect > viewAspect) {
                // Too wide → crop width (zoom horizontally)
                cropHeight = imageHeight
                cropWidth = (cropHeight * viewAspect).toInt()
            } else {
                // Too tall → crop height (zoom vertically)
                cropWidth = imageWidth
                cropHeight = (cropWidth / viewAspect).toInt()
            }

            // Center the crop
            val cropLeft = (imageWidth - cropWidth) / 2
            val cropTop = (imageHeight - cropHeight) / 2

            val cropRect = Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)

            // Compress to JPEG the cropped region
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(cropRect, quality, out)
            out.toByteArray()

        } catch (e: Exception) {
            Log.e("ArCoreView", "JPEG compression error: ${e.message}")
            null
        }
    }


    fun convertAndroidImageTORotatedNv12(image: Image): Pair<ByteArray, ByteArray> {
        val desBufferNv12 = Nv12Buffer.allocate(image.height, image.width)
        val desBufferNv21 = Nv21Buffer.allocate(image.height, image.width)

        val nv12 = image.toNv12Buffer()
        nv12.rotate(desBufferNv12, RotateMode.ROTATE_90)
        desBufferNv12.convertTo(desBufferNv21)


        val nv12byteArray = desBufferNv12.asByteArray()
        val nv21ByteArray = desBufferNv21.asByteArray()

        nv12.close()
        desBufferNv21.close()
        desBufferNv12.close()

        return Pair(nv12byteArray, nv21ByteArray)
    }


    fun generateThumbnailFromNV21(
        nv21: ByteArray,
        width: Int,
        height: Int,
        videoPath: String,
        quality: Int = 95
    ): Boolean {

        // Compute thumbnail path
        val file = File(videoPath)
        val parentDir = file.parent ?: ""
        val nameWithoutExt = file.nameWithoutExtension
        val thumbFileName = "${nameWithoutExt}_thumbnail.jpg"
        val thumbnailPath = if (parentDir.isNotEmpty()) "$parentDir/$thumbFileName" else thumbFileName

        Log.d("Thumbnail", "Generating thumbnail for video: $videoPath")
        Log.d("Thumbnail", "Thumbnail will be saved to: $thumbnailPath")

        // Save NV21 to the computed path
        val result = saveNV21ToFile(nv21, width, height, thumbnailPath, quality)

        if (result) {
            Log.d("Thumbnail", "Thumbnail saved successfully!")
        } else {
            Log.e("Thumbnail", "Failed to save thumbnail.")
        }

        return result
    }

    fun saveNV21ToFile(
        nv21: ByteArray,
        width: Int,
        height: Int,
        outputPath: String,
        quality: Int = 95
    ): Boolean {
        return try {
            Log.d("[Thumbnail]", "Saving NV21 image of size: ${nv21.size} bytes")
            Log.d("[Thumbnail]", "Image dimensions: ${width}x${height}")
            Log.d("[Thumbnail]", "Output path: $outputPath")

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)

            val file = File(outputPath)
            file.parentFile?.mkdirs()

            FileOutputStream(file).use { fos ->
                yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, fos)
            }

            Log.d("[Thumbnail]", "Image saved successfully.")
            true
        } catch (e: Exception) {
            Log.e("[Thumbnail]", "Error saving NV21 image: ${e.message}", e)
            false
        }
    }






}