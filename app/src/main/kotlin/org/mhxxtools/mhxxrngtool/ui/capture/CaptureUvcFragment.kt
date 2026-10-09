package org.mhxxtools.mhxxrngtool.ui.capture

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File

/**
 * AUSBC 3.2.7 対応 UVC プレビュー (ANYOYO / nExt Camera と同じ経路)
 */
class CaptureUvcFragment : CameraFragment() {

    private lateinit var container: FrameLayout
    private var textureView: AspectRatioTextureView? = null

    var onStatus: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        this.container = FrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }
        return this.container
    }

    override fun getCameraView(): IAspectRatio {
        val tv = AspectRatioTextureView(requireContext())
        textureView = tv
        return tv
    }

    override fun getCameraViewContainer(): ViewGroup = container

    override fun getGravity(): Int = Gravity.CENTER

    /** 3.2.7 では getCameraRequest が private なので Client ごと差し替え */
    override fun getCameraClient(): CameraClient {
        return CameraClient.newBuilder(requireContext())
            .setEnableGLES(true)
            .setRawImage(false)
            .setCameraStrategy(CameraUvcStrategy(requireContext()))
            .setCameraRequest(
                CameraRequest.Builder()
                    .setFrontCamera(false)
                    .setPreviewWidth(1280)
                    .setPreviewHeight(720)
                    .create()
            )
            .setDefaultRotateType(RotateType.ANGLE_0)
            .openDebug(false)
            .build()
    }

    override fun initData() {
        super.initData()
        onStatus?.invoke("UVC初期化完了（接続するとプレビュー開始）")
    }

    fun takeSnapshot(onDone: (File?) -> Unit) {
        if (!isCameraOpened()) {
            onError?.invoke("カメラ未接続")
            onDone(null)
            return
        }
        val out = File(requireContext().cacheDir, "uvc_snap_${System.currentTimeMillis()}.jpg")
        captureImage(object : ICaptureCallBack {
            override fun onBegin() {
                onStatus?.invoke("撮影中…")
            }

            override fun onError(error: String?) {
                onError?.invoke(error ?: "撮影失敗")
                onDone(null)
            }

            override fun onComplete(path: String?) {
                if (path.isNullOrBlank()) {
                    onDone(null)
                    return
                }
                val f = File(path)
                if (f.exists()) {
                    onStatus?.invoke("撮影完了")
                    onDone(f)
                } else {
                    onDone(null)
                }
            }
        }, out.absolutePath)
    }
}
