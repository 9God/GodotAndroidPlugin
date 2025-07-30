// TODO: Update to match your plugin's package name.
package org.godotengine.plugin.android.godotandroidplugin

import android.util.Log
import android.widget.Toast
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.UsedByGodot

import android.Manifest
import android.content.pm.PackageManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.camera.core.ImageProxy
import androidx.camera.core.AspectRatio
import androidx.camera.core.resolutionselector.ResolutionSelector

import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.util.Size
import androidx.camera.core.CameraControl
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.nio.ByteBuffer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

class GodotAndroidPlugin(godot: Godot): GodotPlugin(godot) {
    override fun getPluginName() = BuildConfig.GODOT_PLUGIN_NAME

    private var callbackId: Long = 0
    private var callbackMethod = ""

    @UsedByGodot
    fun setCallbackWithSoloStringParam(instanceId: Long, method: String) {
        callbackId = instanceId
        callbackMethod = method
    }

    /**
     * Example showing how to declare a method that's used by Godot.
     *
     * Shows a 'Hello World' toast.
     */
    @UsedByGodot
    fun helloWorld() {
        runOnUiThread {
            Toast.makeText(activity, "Hello World", Toast.LENGTH_LONG).show()
            Log.v(pluginName, "Hello World")
        }
    }

    // 摄像机流逻辑===================================================================================
    private val CAMERA_PERMISSION_REQUEST_CODE = 1001
    // 线程池（单线程，确保 Frame 处理顺序性）
    private var cameraExecutor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null  // 声明为可空变量，便于后续清理

    // 前置摄像机还是后置摄像机
    private var cameraId = AtomicInteger(0)
    // 当前摄像机的长款
    private var cameraWidth = AtomicInteger(0)
    private var cameraHeight = AtomicInteger(0)
    // 双缓冲区
    private var dynamicFrame: ByteArray = ByteArray(0)
    private var stableFrame: ByteArray = ByteArray(0)
    // 摄像机变焦控制
    private var cameraControl: CameraControl? = null

    @UsedByGodot
    fun startCamera(cameraIdValue: Int) {
        val activity = godot.getActivity()?: return
        if (imageAnalysis != null || cameraExecutor != null) {
            return
        }
        cameraId.set(cameraIdValue)

        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            setupCamera()
        }
    }

    @UsedByGodot
    fun stopCamera() {
        val activity = godot.getActivity()?: return

        if (imageAnalysis == null || cameraExecutor == null) {
            return
        }
        cameraControl = null

        // 1. 正确释放 CameraX 资源
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(activity) // 使用插件持有的 context
            val cameraProvider = cameraProviderFuture.get() // 阻塞获取实例
            cameraProvider.unbindAll() // 解除所有绑定（包括 ImageAnalysis）
        } catch (e: Exception) {
            Log.e(pluginName, "Failed to unbind camera: ${e.message}")
        }
        // 3. 显式释放 ImageAnalysis（可选，但更安全）
        imageAnalysis = null // 清除引用，帮助 GC 回收

        // 2. 安全关闭线程池（避免竞态条件）
        cameraExecutor?.let { executor ->
            if (!executor.isShutdown) {
                try {
                    executor.shutdown() // 关闭线程池
                } catch (e: Exception) {
                    Log.e(pluginName, "Failed to shutdown thread pool: ${e.message}")
                }
            }
        }
        cameraExecutor = null
        cameraWidth.set(0)
        cameraHeight.set(0)

        synchronized(this) {
            stableFrame.fill(0)
        }
    }

    // Godot 将调用此方法来获取最新的帧
    @UsedByGodot
    fun getCameraRGBA8888Frame(): ByteArray {
        synchronized(this) {
            if (stableFrame.size != dynamicFrame.size) {
                stableFrame = ByteArray(dynamicFrame.size)
            }
            // 参数：源数组、源起始位置、目标数组、目标起始位置、拷贝长度
            System.arraycopy(dynamicFrame, 0, stableFrame, 0, dynamicFrame.size)
        }
        return stableFrame
    }

    @UsedByGodot
    fun getCameraHW(): IntArray {
        val hw = IntArray(2)
        hw[0] = cameraWidth.get()      // 第一个整数
        hw[1] = cameraHeight.get()     // 第二个整数
        return hw
    }

    @UsedByGodot
    fun setCameraZoom(zoom: Int) {
        godot.getActivity()?: return
        if (cameraControl != null) {
            val zoomValue = zoom.coerceIn(50, 200) // 强制限制在 [50, 200] 范围内
            try {
                cameraControl?.setZoomRatio(zoomValue.toFloat() / 100.0f)
            } catch (e: Exception) {
                Log.e(pluginName, "Failed to set camera zoom: ${e.message}")
            }
        }
    }

    private fun setupCamera() {
        val activity = godot.getActivity()?: return

        // 1. 获取 CameraProvider 实例（绑定到 Activity 生命周期）
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                // 2. 获取 CameraProvider 并释放旧资源（避免重复绑定）
                cameraProvider.unbindAll()

                // 方案1：按首选宽高比自动选择分辨率（推荐）
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(
                        AspectRatio.RATIO_4_3, // 目标宽高比
                        AspectRatioStrategy.FALLBACK_RULE_AUTO // 回退规则
                    )) // 优先选择 16:9 的分辨率
                    .setResolutionStrategy(ResolutionStrategy(
                        Size(1600, 1200), // 目标分辨率
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER // 回退规则
                    ))
                    .build()

                // 3. 配置 ImageAnalysis
                imageAnalysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 只保留最新帧
                    .build()

                val cameraId = cameraId.get()
                // 4. 绑定生命周期和用例
                val cameraSelector = if (cameraId == 0) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                val camera = cameraProvider.bindToLifecycle(
                    activity as LifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )
                cameraControl = camera.cameraControl

                if (cameraExecutor == null) {
                    cameraExecutor = Executors.newSingleThreadExecutor()
                }

                cameraExecutor?.let { executor ->
                    // 5. 设置 Analyzer（在单独线程处理帧数据）
                    imageAnalysis?.setAnalyzer(executor) { imageProxy ->
                        cameraHeight.set(imageProxy.height)
                        cameraWidth.set(imageProxy.width)
                        processImageProxy(imageProxy)
                    }
                }

            } catch (e: Exception) {
                Log.e(pluginName, "Failed to setup camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    /**
     * 处理 ImageProxy 并写入双缓冲区
     */
    private fun processImageProxy(imageProxy: ImageProxy) {
        val validBytesPerRow = cameraWidth.get() * 4;
        val height = cameraHeight.get()
        val validTotalBytes = height * validBytesPerRow;
        // 同步块保护写入操作
        synchronized(this) {
            // 1. 获取图像平面（Plane）数据
            val plane = imageProxy.planes[0] // RGBA_8888 是单平面格式（Single-Plane）
            val buffer: ByteBuffer = plane.buffer // 获取图像数据的 ByteBuffer
            if (dynamicFrame.size != validTotalBytes) {
                dynamicFrame = ByteArray(validTotalBytes)
            }
            val rowStride = plane.rowStride
            if (rowStride > validBytesPerRow) {
                var targetOffset = 0
                // 逐行拷贝
                for (y in 0 until height) {
                    // 从 Buffer 中读取一行有效数据
                    buffer.position(y * rowStride) // 定位到当前行起始位置
                    buffer.get(dynamicFrame, targetOffset, validBytesPerRow)
                    targetOffset += validBytesPerRow
                }
            } else {
                buffer.get(dynamicFrame)
            }
        }
        imageProxy.close() // 及时释放资源
    }

    override fun onMainRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val activity = godot.getActivity()?: return
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            activity.runOnUiThread {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 做处理
                    if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
                        setupCamera()
                    }
                } else {
                    // Permission denied
                    if (!activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        // User checked "Don't ask again" → Guide to settings
                        Toast.makeText(activity, "Please enable camera permission in Settings", Toast.LENGTH_LONG).show()
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", activity.packageName, null)
                        activity.startActivity(intent)
                    } else {
                        // User simply denied → Show a brief explanation
                        Toast.makeText(activity, "Camera permission is required for this feature", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else if (requestCode == PICK_IMAGE_REQUEST_CODE) {
            activity.runOnUiThread {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 做处理
                    openAlbum()
                } else {
                    // Permission denied
                    if (!activity.shouldShowRequestPermissionRationale(getReadAlbumRequiredPermissions()[0])) {
                        // User checked "Don't ask again" → Guide to settings
                        Toast.makeText(activity, "Please enable album access permission in Settings", Toast.LENGTH_LONG).show()
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", activity.packageName, null)
                        activity.startActivity(intent)
                    } else {
                        // User simply denied → Show a brief explanation
                        Toast.makeText(activity, "Album access is required for this feature", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    // ==================================相册选取=====================================================
    private val PICK_IMAGE_PERMISSION_REQUEST_CODE = 2001
    private val PICK_IMAGE_REQUEST_CODE = 2002

    private var albumImageWidth = 0
    private var albumImageHeight = 0

    private var albumImageRGBA8888Bytes: ByteArray = ByteArray(0)

    @UsedByGodot
    fun selectFromAlbum() {
        val activity = godot.getActivity()?: return
        if (hasReadAlbumPermissions(activity)) {
            activity.requestPermissions(getReadAlbumRequiredPermissions(), PICK_IMAGE_PERMISSION_REQUEST_CODE)
        } else {
            openAlbum()
        }
    }

    @UsedByGodot
    fun getAlbumImageHW(): IntArray {
        val hw = IntArray(2)
        hw[0] = albumImageWidth    // 第一个整数
        hw[1] = albumImageHeight     // 第二个整数
        return hw
    }

    @UsedByGodot
    fun getAlbumImageRGBA8888(): ByteArray {
        return albumImageRGBA8888Bytes
    }

    /**
     * 检查是否已授予权限
     */
    private fun hasReadAlbumPermissions(activity: Activity): Boolean {
        return getReadAlbumRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 定义权限列表（根据 API 版本动态选择）
    private fun getReadAlbumRequiredPermissions(): Array<String> {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+) 使用 READ_MEDIA_IMAGES
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            // Android 12 及以下使用 READ_EXTERNAL_STORAGE
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun openAlbum() {
        val activity = godot.getActivity()?: return
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        activity.startActivityForResult(intent, PICK_IMAGE_REQUEST_CODE)
    }

    override fun onMainActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onMainActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val imageUri: Uri? = data?.data
            imageUri?.let { uri ->
                val activity = godot.getActivity()?: return
                albumImageRGBA8888Bytes.fill(0)
                albumImageWidth = 0
                albumImageHeight = 0
                val rawBytes = uriToByteArray(activity, uri)
                if (rawBytes == null) {
                    return
                }
                // 将 ByteArray 转为 Bitmap
                // 1. 指定解码为 ARGB_8888 格式（确保有 Alpha 通道，哪怕不透明）
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
                if (bitmap == null) {
                    return
                }

                albumImageWidth = bitmap.width
                albumImageHeight = bitmap.height
                val pixelCount = bitmap.width * bitmap.height
                val pixels = IntArray(pixelCount)
                bitmap.getPixels(pixels, 0, albumImageWidth, 0, 0, albumImageWidth, albumImageHeight)
                if (albumImageRGBA8888Bytes.size != pixelCount * 4) {
                    albumImageRGBA8888Bytes = ByteArray(pixelCount * 4)
                }

                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val a = (pixel shr 24) and 0xFF
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    // 现在你有了每个像素的 R G B A （每个范围 0~255）
                    albumImageRGBA8888Bytes[i * 4 + 0] = r.toByte()
                    albumImageRGBA8888Bytes[i * 4 + 1] = g.toByte()
                    albumImageRGBA8888Bytes[i * 4 + 2] = b.toByte()
                    albumImageRGBA8888Bytes[i * 4 + 3] = a.toByte()
                }

                // Toast.makeText(activity, "相册照片读取成功", Toast.LENGTH_LONG).show()
                if (callbackMethod.isNotEmpty() && callbackId > 0) {
                    val args = arrayOf("AlbumImageLoadFinish") // 字符串参数
                    org.godotengine.godot.GodotLib.calldeferred(callbackId, callbackMethod, args)
                }
            }
        }
    }

    private fun uriToByteArray(activity: Activity, imageUri: Uri): ByteArray? {
        return try {
            // 1. 通过 ContentResolver 打开 InputStream
            val inputStream = activity.contentResolver.openInputStream(imageUri)
            if (inputStream == null) {
                null
            } else {
                // 2. 使用 ByteArrayOutputStream 读取字节到内存
                val byteBuffer = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    byteBuffer.write(buffer, 0, bytesRead)
                }
                byteBuffer.flush()
                // 3. 得到最终的 ByteArray
                val byteArray = byteBuffer.toByteArray()
                // 4. 关闭流
                inputStream.close()
                byteBuffer.close()
                byteArray
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    // ==================================拍照=====================================================
    // 拍照用上述摄像机方法获取一帧数据来模拟而不是使用系统照相机
}
