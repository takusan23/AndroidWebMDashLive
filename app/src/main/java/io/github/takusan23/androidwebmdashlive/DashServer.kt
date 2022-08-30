package io.github.takusan23.androidwebmdashlive

import android.annotation.SuppressLint
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * index.htmlとマニフェストとセグメントファイルをホスティングする
 *
 * @param portNumber ポート番号
 * @param segmentIntervalSec セグメント生成間隔
 * @param segmentFileNamePrefix セグメントファイルのプレフィックス
 * @param staticHostingFolder セグメントファイルを保存しているフォルダ
 */
class DashServer(
    private val portNumber: Int,
    private val segmentIntervalSec: Int,
    private val segmentFileNamePrefix: String,
    private val staticHostingFolder: File,
) {

    /**
     * ISO 8601 で映像データの利用可能時間を指定する必要があるため
     * MPEG-DASHの場合は指定時間になるまで再生を開始しない機能があるらしい。
     */
    @SuppressLint("NewApi")
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.JAPAN)

    /** マニフェスト */
    private var manifest: String? = null

    /** サーバー */
    private val server = embeddedServer(Netty, port = portNumber) {
        routing {
            // WebSocketと動画プレイヤーを持った簡単なHTMLを返す
            get("/") {
                call.respondText(INDEX_HTML, ContentType.parse("text/html"))
            }
            // MPEG-DASHのマニフェストを返す
            get("manifest.mpd") {
                call.respondText(manifest!!, ContentType.parse("text/html"))
            }
            // 静的ファイル公開するように。
            // マニフェスト、動画を配信する
            static {
                staticRootFolder = staticHostingFolder
                files(staticHostingFolder)
            }
        }
    }

    /** サーバーを開始する */
    fun startServer() {
        manifest = createManifest()
        server.start()
    }

    /** サーバーを終了する */
    fun stopServer() {
        server.stop()
    }

    /** マニフェストを作って返す */
    private fun createManifest(): String {
        val availableTime = isoDateFormat.format(System.currentTimeMillis())
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" availabilityStartTime="$availableTime" maxSegmentDuration="PT${segmentIntervalSec}S" minBufferTime="PT${segmentIntervalSec}S" type="dynamic" profiles="urn:mpeg:dash:profile:isoff-live:2011,http://dashif.org/guidelines/dash-if-simple">
              <BaseURL>/</BaseURL>
              <Period start="PT0S">
                <AdaptationSet mimeType="video/webm">
                  <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main" />
                  <!-- duration が更新頻度っぽい -->
                  <SegmentTemplate duration="$segmentIntervalSec" initialization="/init.webm" media="/${segmentFileNamePrefix}${"$"}Number${'$'}.webm" startNumber="0"/>
                    <!-- 音声入れる場合は codecs="vp9,opus" -->
                  <Representation id="default" codecs="vp9"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
    }

    companion object {

        /** index.html */
        private const val INDEX_HTML = """
<!doctype html>
<html>
<head>
    <title>AndroidからMPEG-DASH配信</title>
    <style>
        video {
            width: 640px;
            height: 360px;
        }
    </style>
</head>
<body>
    <div>
        <video id="videoPlayer" controls muted autoplay></video>
    </div>
    <script src="https://cdn.dashjs.org/latest/dash.all.debug.js"></script>
    <script>
        (function () {
            var url = "manifest.mpd";
            var player = dashjs.MediaPlayer().create();
            player.initialize(document.querySelector("#videoPlayer"), url, true);
        })();
    </script>
</body>
</html>
"""

    }
}