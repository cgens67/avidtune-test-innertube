package com.cgens67.avidtune.ytmusic

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val NEXT_ENDPOINT = "/youtubei/v1/next"
    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""
    private const val PROBE_TIMEOUT_SECONDS = 5L
    private const val PROBE_READ_BYTES = 1024L
    private val REFUSAL_CODES = setOf(403, 404, 410)

    data class Stream(
        val url: String,
        val kbps: Int,
        val mimeType: String,
        val loudnessDb: Double? = null
    )

    private val extractorClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            .pingInterval(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val proberClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private class OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            if (NEXT_ENDPOINT in request.url()) {
                val bytes = EMPTY_NEXT_RESPONSE.toByteArray()
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, bytes, request.url())
            }

            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }

            if (!hasUserAgent) {
                builder.header("User-Agent", PlayerClient.WEB_USER_AGENT)
            }

            val response = extractorClient.newCall(builder.build()).execute()
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }

            val bodyString = response.body?.string()
            val bodyBytes = bodyString?.toByteArray()
            val latestUrl = response.request.url.toString()

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                bodyBytes,
                latestUrl
            )
        }

        override fun executeAsync(request: Request, callback: AsyncCallback?): CancellableCall {
            throw UnsupportedOperationException()
        }
    }

    private val newPipeInit by lazy {
        if (NewPipe.getDownloader() == null) {
            NewPipe.init(OkHttpDownloader())
        }
    }

    private val recent = ConcurrentHashMap<String, Pair<String, Long>>()
    private val inFlight = ConcurrentHashMap<String, Deferred<String>>()
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractionGate = Mutex()

    suspend fun resolve(videoId: String): String = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        recent[videoId]?.let { (url, time) ->
            if (now - time < 20 * 60 * 1000L) return@withContext url
        }

        inFlight[videoId]?.let { return@withContext it.await() }

        val task = resolverScope.async(start = CoroutineStart.LAZY) {
            resolveUncached(videoId)
        }

        val running = inFlight.putIfAbsent(videoId, task)
        if (running != null) {
            task.cancel()
            return@withContext running.await()
        }

        task.invokeOnCompletion { inFlight.remove(videoId, task) }
        task.start()
        task.await()
    }

    private suspend fun resolveUncached(videoId: String): String {
        newPipeInit
        val stream = extractStreamWithRetry(videoId)
        recent[videoId] = stream.url to SystemClock.elapsedRealtime()
        return stream.url
    }

    private suspend fun extractStreamWithRetry(videoId: String): Stream {
        var failure: Exception? = null
        for (attempt in 1..2) {
            if (attempt > 1) delay(500L)
            try {
                val stream = extractStream(videoId)
                val probeResult = probe(stream.url)
                if (probeResult != Probe.REFUSED) {
                    return stream
                }
                Timber.w("Stream URL probe refused (HTTP 403/404) for $videoId on attempt $attempt")
            } catch (e: Exception) {
                Timber.w(e, "Extraction attempt $attempt failed for $videoId")
                failure = e
            }
        }
        throw failure ?: IOException("Failed to extract verified stream for $videoId")
    }

    private suspend fun extractStream(videoId: String): Stream = extractionGate.withLock {
        withContext(Dispatchers.IO) {
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()

            val candidates = extractor.audioStreams
                .filter {
                    !it.content.isNullOrBlank() &&
                        (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod != null)
                }

            val chosen = candidates.maxByOrNull { it.averageBitrate }
                ?: extractor.audioStreams.filter { !it.content.isNullOrBlank() }.maxByOrNull { it.averageBitrate }
                ?: throw IOException("No audio streams available for $videoId")

            Stream(
                url = chosen.content,
                kbps = chosen.averageBitrate,
                mimeType = chosen.format?.mimeType ?: "audio/webm"
            )
        }
    }

    private enum class Probe { OK, REFUSED, UNREACHABLE }

    private fun probe(url: String): Probe {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("Range", "bytes=0-$PROBE_READ_BYTES")

        PlayerClient.forStreamUrl(url).mediaHeaders().forEach { (k, v) ->
            builder.header(k, v)
        }

        return try {
            proberClient.newCall(builder.build()).execute().use { response ->
                when {
                    response.code in REFUSAL_CODES -> Probe.REFUSED
                    response.code in 200..299 || response.code == 416 -> Probe.OK
                    else -> Probe.UNREACHABLE
                }
            }
        } catch (e: Exception) {
            Probe.UNREACHABLE
        }
    }

    fun onPlaybackRefused(url: String, responseCode: Int) {
        if (responseCode in REFUSAL_CODES) {
            recent.entries.firstOrNull { it.value.first == url }?.let {
                recent.remove(it.key)
                Timber.w("Invalidated cached stream for ${it.key} due to HTTP $responseCode")
            }
        }
    }
}
