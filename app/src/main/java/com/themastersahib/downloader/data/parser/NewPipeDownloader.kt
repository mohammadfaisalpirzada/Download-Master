package com.themastersahib.downloader.data.parser

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException

class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val reqBuilder = okhttp3.Request.Builder().url(url)

        headers?.forEach { (headerName, headerValues) ->
            headerValues.forEach { value ->
                reqBuilder.addHeader(headerName, value)
            }
        }

        // Ensure modern browser user agent
        if (headers == null || !headers.containsKey("User-Agent")) {
            reqBuilder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
        }

        when (httpMethod.uppercase()) {
            "GET" -> reqBuilder.get()
            "HEAD" -> reqBuilder.head()
            "POST" -> {
                val contentType = headers?.get("Content-Type")?.firstOrNull()
                val mediaType = contentType?.toMediaTypeOrNull()
                val body = (dataToSend ?: ByteArray(0)).toRequestBody(mediaType)
                reqBuilder.post(body)
            }
            else -> reqBuilder.method(httpMethod, null)
        }

        client.newCall(reqBuilder.build()).execute().use { okResponse ->
            if (okResponse.code == 429) {
                throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException(
                    "YouTube is rate limiting this network (429)",
                    url
                )
            }
            return Response(
                okResponse.code,
                okResponse.message,
                okResponse.headers.toMultimap(),
                okResponse.body?.string() ?: "",
                okResponse.request.url.toString()
            )
        }
    }
}
