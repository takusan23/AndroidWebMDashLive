package io.github.takusan23.androidwebmdashlive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.takusan23.androidwebmdashlive.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity() {

    private val cameraExecutor by lazy { Executors.newSingleThreadExecutor() }

    // ViewBinding
    private val viewBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    // Camera2 API
    private var cameraDevice: CameraDevice? = null
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    /** 動画エンコーダー */
    private val videoEncoder = VideoEncoder()

    /** 動画コンテナ管理クラス */
    private lateinit var dashContainer: DashContainerWriter

    /** 生成した動画をまとめるクラス */
    private lateinit var contentManager: DashContentManager

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        // 権限ない場合
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "権限がありません", Toast.LENGTH_SHORT).show()
            return
        }

        // 開始ボタン
        viewBinding.startButton.setOnClickListener {
            dashContainer.start()
        }

        // 終了ボタン
        viewBinding.stopButton.setOnClickListener {
            videoEncoder.release()
            dashContainer.release()
        }

        // コールバック関数を回避するためにコルーチンを活用していく
        lifecycleScope.launch {
            val holder = suspendSurfaceHolder(viewBinding.surfaceView)
            cameraDevice = suspendOpenCamera()

            // ファイル管理クラス
            contentManager = DashContentManager(getExternalFilesDir(null)!!, "segment").apply {
                deleteGenerateFile()
            }
            // コンテナフォーマットに書き込むクラス
            dashContainer = DashContainerWriter(contentManager.generateTempFile("temp")).apply {
                resetOrCreateContainerFile()
            }

            // エンコーダーを初期化する
            videoEncoder.prepareEncoder(
                videoWidth = 1280,
                videoHeight = 720,
                bitRate = 1_000_000,
                frameRate = 30,
                isVp9 = true
            )
            // Camera2 API から MediaCodec へ映像を渡すための Surface
            val surface = videoEncoder.createInputSurface()

            // エンコーダーを起動する、動作中は一時停止するので別コルーチンを起動
            launch {
                videoEncoder.startVideoEncode(
                    onOutputBufferAvailable = { byteBuffer, bufferInfo ->
                        dashContainer.writeVideo(byteBuffer, bufferInfo)
                    },
                    onOutputFormatAvailable = { mediaFormat ->
                        dashContainer.setVideoTrack(mediaFormat)
                    }
                )
            }

            // カメラの設定をする
            val outputList = buildList {
                add(OutputConfiguration(holder.surface))
                add(OutputConfiguration(surface))
            }
            val captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(holder.surface)
                addTarget(surface)
            }.build()
            SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    captureSession.setRepeatingRequest(captureRequest, null, null)
                }

                override fun onConfigureFailed(p0: CameraCaptureSession) {

                }
            }).apply { cameraDevice!!.createCaptureSession(this) }

            while (isActive) {
                if (dashContainer.isRunning) {
                    // SEGMENT_INTERVAL_MS 待機したら新しいファイルにする
                    delay(SEGMENT_INTERVAL_MS)
                    // 初回時だけ初期化セグメントを作る
                    if (!dashContainer.isGeneratedInitSegment) {
                        contentManager.createFile(INIT_SEGMENT_FILENAME).also { initSegment ->
                            dashContainer.sliceInitSegmentFile(initSegment.path)
                        }
                    }
                    // MediaMuxerで書き込み中のファイルから定期的にデータをコピーして（セグメントファイルが出来る）クライアントで再生する
                    // この方法だと、MediaMuxerとMediaMuxerからコピーしたデータで二重に容量を使うけど後で考える
                    contentManager.createIncrementFile().also { segment ->
                        dashContainer.sliceSegmentFile(segment.path)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
        dashContainer.release()
        videoEncoder.release()
    }

    /** Surfaceが利用可能になるまで一時停止する */
    private suspend fun suspendSurfaceHolder(surfaceView: SurfaceView) = suspendCoroutine<SurfaceHolder> {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(p0: SurfaceHolder) {
                it.resume(p0)
            }

            override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {

            }

            override fun surfaceDestroyed(p0: SurfaceHolder) {

            }
        })
    }

    /** カメラを開くまで一時停止する */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun suspendOpenCamera() = suspendCoroutine<CameraDevice> {
        val cameraId = cameraManager.cameraIdList[0]
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                it.resume(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
            }


            override fun onError(camera: CameraDevice, p1: Int) {

            }
        }, null)
    }


    companion object {

        /** 生成間隔 */
        private const val SEGMENT_INTERVAL_MS = 3_000L

        /** 初期化セグメントの名前 */
        private const val INIT_SEGMENT_FILENAME = "init.webm"

    }

}