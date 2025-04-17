package com.danmo.guide.feature.camera
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
/**
 * ImageProxy处理工具类，用于YUV格式到Bitmap的转换
 * ImageProxy utility class for YUV format to Bitmap conversion
 */
object ImageProxyUtils {
    /**
     * 将ImageProxy转换为Bitmap（支持YUV_420_888格式）
     * Convert ImageProxy to Bitmap (supports YUV_420_888 format)
     * @param imageProxy CameraX图像代理对象 / CameraX image proxy object
     * @return 转换后的Bitmap，失败返回null / Converted Bitmap, null if failed
     */
    fun toBitmap(imageProxy: ImageProxy): Bitmap? {
        // 转换为NV21格式字节数组 / Convert to NV21 format byte array
        val nv21 = yuv420888ToNv21(imageProxy)
        // 创建YUV图像对象 / Create YUV image object
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        return yuvImage.toBitmap()
    }
    /**
     * 将YuvImage转换为Bitmap
     * Convert YuvImage to Bitmap
     */
    private fun YuvImage.toBitmap(): Bitmap? {
        ByteArrayOutputStream().use { out ->
            // 将YUV数据压缩为JPEG / Compress YUV data to JPEG
            if (!compressToJpeg(Rect(0, 0, width, height), 100, out)) {
                return null
            }
            // 从字节数组解码Bitmap / Decode Bitmap from byte array
            return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        }
    }
    /**
     * 将YUV_420_888格式转换为NV21字节数组
     * Convert YUV_420_888 format to NV21 byte array
     * @param image CameraX图像代理对象 / CameraX image proxy object
     * @return NV21格式字节数组 / NV21 format byte array
     */
    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        // 计算像素总数 / Calculate total pixels
        val pixelCount = image.cropRect.width() * image.cropRect.height()
        // 获取每像素位数 / Get bits per pixel
        val pixelSizeBits = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888)
        // 创建输出缓冲区 / Create output buffer
        val outputBuffer = ByteArray(pixelCount * pixelSizeBits / 8)
        // 执行实际转换 / Perform actual conversion
        imageToByteBuffer(image, outputBuffer, pixelCount)
        return outputBuffer
    }
    /**
     * 将YUV_420_888图像数据填充到字节缓冲区
     * Fill YUV_420_888 image data into byte buffer
     * @param image CameraX图像代理对象 / CameraX image proxy object
     * @param outputBuffer 输出缓冲区 / Output buffer
     * @param pixelCount 像素总数 / Total pixel count
     */
    private fun imageToByteBuffer(image: ImageProxy, outputBuffer: ByteArray, pixelCount: Int) {
        // 验证输入格式 / Verify input format
        check(image.format == ImageFormat.YUV_420_888)
        val imageCrop = image.cropRect   // 图像裁剪区域 / Image crop rectangle
        val imagePlanes = image.planes   // YUV平面数组 / YUV planes array
        imagePlanes.forEachIndexed { planeIndex, plane ->
            // 设置输出参数：跨距和偏移量 / Set output parameters: stride and offset
            var (outputStride, outputOffset) = when (planeIndex) {
                0 -> 1 to 0       // Y平面：跨距1，偏移0 / Y plane: stride 1, offset 0
                1 -> 2 to pixelCount + 1 // U平面：跨距2，偏移Y之后 / U plane: stride 2, offset after Y
                2 -> 2 to pixelCount     // V平面：跨距2，偏移Y之后+1 / V plane: stride 2, offset after Y+1
                else -> return@forEachIndexed
            }
            val planeBuffer = plane.buffer    // 当前平面缓冲区 / Current plane buffer
            val rowStride = plane.rowStride   // 行跨距（字节数） / Row stride in bytes
            val pixelStride = plane.pixelStride // 像素跨距（字节数） / Pixel stride in bytes
            // 计算平面裁剪区域（色度平面需缩小2倍） / Calculate plane crop area (chroma planes scaled down by 2)
            val planeCrop = if (planeIndex == 0) {
                imageCrop
            } else {
                Rect(
                    imageCrop.left / 2,
                    imageCrop.top / 2,
                    imageCrop.right / 2,
                    imageCrop.bottom / 2
                )
            }
            val planeWidth = planeCrop.width()  // 平面宽度 / Plane width
            val planeHeight = planeCrop.height() // 平面高度 / Plane height
            // 计算每行有效数据长度 / Calculate valid row length
            val rowLength = if (pixelStride == 1 && outputStride == 1) {
                planeWidth
            } else {
                (planeWidth - 1) * pixelStride + 1
            }
            // 逐行处理平面数据 / Process plane data row by row
            for (row in 0 until planeHeight) {
                // 定位缓冲区起始位置 / Position buffer start
                planeBuffer.position(
                    (row + planeCrop.top) * rowStride + planeCrop.left * pixelStride
                )
                if (pixelStride == 1 && outputStride == 1) {
                    // 直接复制整行数据 / Directly copy entire row
                    planeBuffer.get(outputBuffer, outputOffset, rowLength)
                    outputOffset += rowLength
                } else {
                    // 处理带跨距的像素数据 / Handle strided pixel data
                    val rowBuffer = ByteArray(rowLength)
                    planeBuffer.get(rowBuffer, 0, rowLength)
                    for (col in 0 until planeWidth) {
                        outputBuffer[outputOffset] = rowBuffer[col * pixelStride]
                        outputOffset += outputStride
                    }
                }
            }
        }
    }
}