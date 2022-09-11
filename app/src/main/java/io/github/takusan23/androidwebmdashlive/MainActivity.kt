package io.github.takusan23.androidwebmdashlive

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
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

    /** 音声エンコーダー */
    private val audioEncoder = AudioEncoder()

    /** マイク */
    private lateinit var audioRecord: AudioRecord

    /** 動画コンテナ管理クラス */
    private lateinit var dashContainer: DashContainerWriter

    /** 生成した動画をまとめるクラス */
    private lateinit var contentManager: DashContentManager

    /** Webサーバー */
    private lateinit var dashServer: DashServer

    /** 権限コールバック */
    @SuppressLint("MissingPermission")
    private val permissionResult = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.all { it.value }) {
            setupAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        // カメラとマイク権限がある場合は準備する
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            setupAll()
        } else {
            // 権限を求める
            permissionResult.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
        }
    }

    @RequiresPermission(allOf = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
    private fun setupAll() {
        lifecycleScope.launch {
            setupCommon()
            setupCameraAndEncoder()
            setupMicAndEncoder()
        }
    }

    /** 共通部分の初期化 */
    private suspend fun setupCommon() {
        // 開始ボタン、セグメント生成とサーバーを起動する
        viewBinding.startButton.setOnClickListener {
            dashServer.startServer()
            dashContainer.start()
        }
        // 終了ボタン
        viewBinding.stopButton.setOnClickListener {
            release()
        }

        // ファイル管理クラス
        contentManager = DashContentManager(getExternalFilesDir(null)!!, SEGMENT_FILENAME_PREFIX).apply {
            deleteCreateFile()
        }
        // コンテナフォーマットに書き込むクラス
        dashContainer = DashContainerWriter(contentManager.createTempFile("temp")).apply {
            createContainerFile()
        }
        // Webサーバー
        dashServer = DashServer(
            portNumber = 8080,
            segmentIntervalSec = (SEGMENT_INTERVAL_MS / 1000).toInt(),
            segmentFileNamePrefix = SEGMENT_FILENAME_PREFIX,
            staticHostingFolder = contentManager.outputFolder
        )
        // WebM セグメントファイルを作る。MediaMuxerが書き込んでるファイルに対して切り出して保存する
        lifecycleScope.launch(Dispatchers.Default) {
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

    /** カメラとエンコーダーを初期化する */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    private fun setupCameraAndEncoder() {
        // コールバック関数を回避するためにコルーチンを活用していく
        lifecycleScope.launch {
            val holder = viewBinding.surfaceView.holder
            cameraDevice = suspendOpenCamera()

            // エンコーダーを初期化する
            videoEncoder.prepareEncoder(
                videoWidth = 1280,
                videoHeight = 720,
                bitRate = 1_000_000,
                frameRate = 30,
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
        }
    }

    /** カメラとエンコーダーを初期化する */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun setupMicAndEncoder() {
        // コールバック関数を回避するためにコルーチンを活用していく
        lifecycleScope.launch {
            // エンコーダーを初期化する
            audioEncoder.prepareEncoder(
                sampleRate = SAMPLE_RATE,
                channelCount = 2,
                bitRate = 192_000,
            )

            // 音声レコーダー起動
            val bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val audioFormat = AudioFormat.Builder().apply {
                setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                setSampleRate(SAMPLE_RATE)
                setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            }.build()
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioRecord.Builder().apply {
                    setAudioFormat(audioFormat)
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setBufferSizeInBytes(bufferSizeInBytes)
                }.build()
            } else {
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes)
            }.apply { startRecording() }

            // エンコーダーを起動する、動作中は一時停止するので別コルーチンを起動
            launch {
                // エンコードする
                audioEncoder.startAudioEncode(
                    onRecordInput = { bytes ->
                        // PCM音声を取り出しエンコする
                        audioRecord.read(bytes, 0, bytes.size)
                    },
                    onOutputBufferAvailable = { byteBuffer, bufferInfo ->
                        dashContainer.writeAudio(byteBuffer, bufferInfo)
                    },
                    onOutputFormatAvailable = { mediaFormat ->
                        dashContainer.setAudioTrack(mediaFormat)
                    }
                )
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
        dashServer.stopServer()
        dashContainer.release()
        cameraDevice?.close()
        videoEncoder.release()
        audioEncoder.release()
        lifecycleScope.cancel()
    }

    companion object {

        /** 生成間隔 */
        private const val SEGMENT_INTERVAL_MS = 1_000L

        /** 初期化セグメントの名前 */
        private const val INIT_SEGMENT_FILENAME = "init.webm"

        /** セグメントファイルのプレフィックス */
        private const val SEGMENT_FILENAME_PREFIX = "segment"

        /** サンプリングレート */
        private const val SAMPLE_RATE = 48_000
    }

}