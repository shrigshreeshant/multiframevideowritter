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



}