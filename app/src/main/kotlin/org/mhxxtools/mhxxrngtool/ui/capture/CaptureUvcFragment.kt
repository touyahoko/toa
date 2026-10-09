package org.mhxxtools.mhxxrngtool.ui.capture

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File

/**
 * AUSBC (AndroidUSBCamera) ベースの UVC プレビュー。
 * nExt Camera / ANYOYO と同じ USB UVC 経路。
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

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(false)
            .create()
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED ->
                onStatus?.invoke("UVCカメラ起動成功（1280x720）")
            ICameraStateCallBack.State.CLOSED ->
                onStatus?.invoke("カメラを閉じました")
            ICameraStateCallBack.State.ERROR ->
                onError?.invoke(msg ?: "カメラエラー")
        }
    }

    /** JPEG を一時ファイルに保存してパスを返す */
    fun takeSnapshot(onDone: (File?) -> Unit) {
        val dir = requireContext().cacheDir
        val out = File(dir, "uvc_snap_${System.currentTimeMillis()}.jpg")
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
