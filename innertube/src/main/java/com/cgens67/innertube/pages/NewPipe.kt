package com.cgens67.innertube.pages

import com.cgens67.innertube.YouTube
import com.cgens67.innertube.models.YouTubeClient
import com.cgens67.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

private class NewPipeDownloaderImpl(
    proxy: Proxy?,
    proxyAuth: String?,
) : Downloader() {

    private val NEXT_ENDPOINT = "/youtubei/v1/next"
    private val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""

    private val client =
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            .pingInterval(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .apply {
                proxy?.let { proxy(it) }
                proxyAuth?.let { auth ->
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder()
                            .header("Proxy-Authorization", auth)
                            .build()
                    }
                }
            }.build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        // BitChord fix: intercept and drop the next endpoint so extraction doesn't hang for 12s
        if (NEXT_ENDPOINT in request.url()) {
            val bytes = EMPTY_NEXT_RESPONSE.toByteArray()
            return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, bytes, request.url())
        }

        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder =
            okhttp3.Request.Builder()
                .method(httpMethod, dataToSend?.toRequestBody())
                .url(url)
                .header("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerName.equals("User-Agent", ignoreCase = true)) return@forEach
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val latestUrl = response.request.url.toString()
        val bodyString = response.body?.string()
        val bodyBytes = bodyString?.toByteArray()

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            bodyString,
            bodyBytes,
            latestUrl,
        )
    }

    override fun executeAsync(request: Request, callback: AsyncCallback?): CancellableCall {
        throw UnsupportedOperationException()
    }
}

object NewPipeUtils {
    init {
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy, YouTube.proxyAuth))
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            val url =
                format.url ?: format.signatureCipher?.let { signatureCipher ->
                    val params = parseQueryString(signatureCipher)
                    val obfuscatedSignature =
                        params["s"]
                            ?: throw ParsingException("Could not parse cipher signature")
                    val signatureParam =
                        params["sp"]
                            ?: throw ParsingException("Could not parse cipher signature parameter")
                    val url =
                        params["url"]?.let { URLBuilder(it) }
                            ?: throw ParsingException("Could not parse cipher url")
                    url.parameters[signatureParam] =
                        YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                            videoId,
                            obfuscatedSignature,
                        )
                    url.toString()
                } ?: throw ParsingException("Could not find format url")

            return@runCatching YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url,
            )
        }
}

object NewPipeExtractor {
    fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        return try {
            val streamInfo =
                StreamInfo.getInfo(
                    NewPipe.getService(0),
                    "https://www.youtube.com/watch?v=$videoId",
                )
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            streamsList.mapNotNull {
                (it.itagItem?.id ?: return@mapNotNull null) to it.content
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): String? {
        return try {
            val url = format.url ?: format.signatureCipher?.let { signatureCipher ->
                val params = parseQueryString(signatureCipher)
                val obfuscatedSignature = params["s"]
                    ?: throw ParsingException("Could not parse cipher signature")
                val signatureParam = params["sp"]
                    ?: throw ParsingException("Could not parse cipher signature parameter")
                val url = params["url"]?.let { URLBuilder(it) }
                    ?: throw ParsingException("Could not parse cipher url")
                url.parameters[signatureParam] =
                    YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                        videoId,
                        obfuscatedSignature
                    )
                url.toString()
            } ?: throw ParsingException("Could not find format url")

            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url,
            )
        } catch (e: Exception) {
            null
        }
    }
}
