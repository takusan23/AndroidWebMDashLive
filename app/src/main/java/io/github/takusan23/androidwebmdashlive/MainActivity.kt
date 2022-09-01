package io.github.takusan23.androidwebmdashlive

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.takusan23.androidwebmdashlive.databinding.ActivityMainBinding
import kotlinx.coroutines.*
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

    /** Webサーバー */
    private lateinit var dashServer: DashServer

    /** 権限コールバック */
    @SuppressLint("MissingPermission")
    private val permissionResult = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {
            setupCameraAndEncoder()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        // カメラ権限がある場合は準備する
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupCameraAndEncoder()
        } else {
            // 権限を求める
            permissionResult.launch(android.Manifest.permission.CAMERA)
        }
    }

    /** カメラとエンコーダーを初期化する */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    private fun setupCameraAndEncoder() {

        // 開始ボタン、セグメント生成とサーバーを起動する
        viewBinding.startButton.setOnClickListener {
            dashServer.startServer()
            dashContainer.start()
        }

        // 終了ボタン
        viewBinding.stopButton.setOnClickListener {
            release()
        }

        // コールバック関数を回避するためにコルーチンを活用していく
        lifecycleScope.launch {
            val holder = viewBinding.surfaceView.holder
            cameraDevice = suspendOpenCamera()

            // ファイル管理クラス
            contentManager = DashContentManager(getExternalFilesDir(null)!!, SEGMENT_FILENAME_PREFIX).apply {
                // 今までのファイルを消す
                deleteGenerateFile()
            }
            // コンテナフォーマットに書き込むクラス
            dashContainer = DashContainerWriter(contentManager.generateTempFile("temp")).apply {
                createContainerFile()
            }

            // Webサーバー
            dashServer = DashServer(
                portNumber = 8080,
                segmentIntervalSec = (SEGMENT_INTERVAL_MS / 1000).toInt(),
                segmentFileNamePrefix = SEGMENT_FILENAME_PREFIX,
                staticHostingFolder = contentManager.outputFolder
            )

            // エンコーダーを初期化する
            videoEncoder.prepareEncoder(
                videoWidth = 1280,
                videoHeight = 720,
                bitRate = 1_000_000,
                frameRate = 30,
                isVp9 = true
            )
            // Camera2 API から MediaCodec へ映像を渡すための Surface
            val inputSurface = videoEncoder.createInputSurface()

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

            // 出力先Surface。プレビューとVP9にエンコードするMediaCodec
            val outputSurfaceList = listOf(holder.surface, inputSurface)
            // カメラの設定をする
            val captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                outputSurfaceList.forEach {
                    addTarget(it)
                }
            }.build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputList = buildList {
                    outputSurfaceList.forEach {
                        add(OutputConfiguration(it))
                    }
                }
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        captureSession.setRepeatingRequest(captureRequest, null, null)
                    }

                    override fun onConfigureFailed(p0: CameraCaptureSession) {

                    }
                }).apply { cameraDevice!!.createCaptureSession(this) }
            } else {
                cameraDevice!!.createCaptureSession(outputSurfaceList, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        captureSession.setRepeatingRequest(captureRequest, null, null)
                    }

                    override fun onConfigureFailed(p0: CameraCaptureSession) {

                    }
                }, null)
            }
            // WebM セグメントファイルを作る。MediaMuxerが書き込んでるファイルに対して切り出して保存する
            launch(Dispatchers.Default) {
                while (isActive) {
                    if (dashContainer.isRunning) {
                        runCatching {
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
                        }.onFailure { it.printStackTrace() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
    }

    /** カメラを開くまで一時停止する */
    @RequiresPermission(android.Manifest.permission.CAMERA)
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

    private fun release() {
        dashContainer.release()
        cameraDevice?.close()
        videoEncoder.release()
        dashServer.stopServer()
        lifecycleScope.cancel()
    }

    companion object {

        /** 生成間隔 */
        private const val SEGMENT_INTERVAL_MS = 3_000L

        /** 初期化セグメントの名前 */
        private const val INIT_SEGMENT_FILENAME = "init.webm"

        /** セグメントファイルのプレフィックス */
        private const val SEGMENT_FILENAME_PREFIX = "segment"
    }

}