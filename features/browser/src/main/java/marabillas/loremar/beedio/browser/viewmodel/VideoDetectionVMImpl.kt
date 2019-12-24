/*
 * Beedio is an Android app for downloading videos
 * Copyright (C) 2019 Loremar Marabillas
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package marabillas.loremar.beedio.browser.viewmodel

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import marabillas.loremar.beedio.base.mvvm.SendLiveData
import marabillas.loremar.beedio.base.web.HttpNetwork
import timber.log.Timber
import java.net.URL
import java.util.*

class VideoDetectionVMImpl : VideoDetectionVM() {
    override val foundVideos: List<FoundVideo>
        get() = _foundVideos

    private val _foundVideos = mutableListOf<FoundVideo>()
    private val filters = arrayOf("mp4", "video", "googleusercontent", "embed")
    private val network = HttpNetwork()

    private val sendFoundVideo = SendLiveData<FoundVideo>()
    private val isAnalyzing = MutableLiveData<Boolean>()

    private var analysisCount = 0

    init {
        isAnalyzing.value = false
    }

    override fun analyzeUrlForVideo(url: String, title: String, sourceWebPage: String) {
        viewModelScope.launch(Dispatchers.IO) {
            onStartAnalysis()

            filter(url) {
                Timber.i("Analyzing $url")
                connect(url) {
                    if (contentType().containsVideoOrAudio())
                        extractVideo(title, sourceWebPage)
                    else if (contentType().isM3U8())
                        extractM3U8Video(title, sourceWebPage)
                }
            }

            onEndAnalysis()
        }
    }

    private fun onStartAnalysis() {
        analysisCount++
        if (analysisCount == 1)
            isAnalyzing.postValue(true)
    }

    private fun onEndAnalysis() {
        analysisCount--
        if (analysisCount == 0)
            isAnalyzing.postValue(false)
    }

    private fun filter(url: String, doIfTrue: (String) -> Unit) {
        for (filter in filters)
            if (url.contains(filter, true))
                doIfTrue(url)
    }

    private fun HttpNetwork.Connection.contentType() = getResponseHeader("Content-Type")?.toLowerCase(Locale.US)
            ?: ""

    private fun String.containsVideoOrAudio() = contains("video") || contains("audio")

    private fun String.isM3U8() = equals("application/x-mpegurl") || equals("application/vnd.apple.mpegurl")

    private fun HttpNetwork.Connection.extractVideo(
            title: String,
            sourceWebPage: String
    ) {
        val host = URL(sourceWebPage).host
        val contentType = contentType()

        if (host.contains("twitter.com") && contentType == "video/mp2t")
            return

        var url = getResponseHeader("Location") ?: urlHandler.url ?: return
        var size = getResponseHeader("Content-Length") ?: "0"
        var name = when {
            title.isNotBlank() -> title
            contentType.contains("audio") -> "audio"
            else -> "video"
        }
        var sourceWebsite = ""
        var isChunked = false

        if (host.contains("youtube.com") || urlHandler.host?.contains("googlevideo.com") == true) {
            url = url.substringBeforeLast("&range").also {
                connect(it) {
                    size = getResponseHeader("Content-Length") ?: "0"
                    name = getYoutubeVideoTitle(sourceWebPage)
                }
            }
        } else if (host.contains("dailymotion.com")) {
            isChunked = true
            sourceWebsite = "dailymotion.com"
            url = url.replace("(frag\\()+(\\d+)+(\\))", "FRAGMENT")
            size = "0"
        } else if (host.contains("vimeo.com") && url.endsWith("m4s")) {
            isChunked = true
            sourceWebsite = "vimeo.com"
            url = url.replace("(segment-)+(\\d+)", "SEGMENT")
            size = "0"
        } else if (host.contains("facebook.com") && url.contains("bytestart")) {
            url = "https://video.xx.fbcdn${url.substringAfter("fbcdn").substringBeforeLast("&bytestart")}"
            sourceWebsite = "facebook.com"
            connect(url) {
                size = getResponseHeader("Content-Length") ?: "0"
            }
        }

        val ext = getExtensionFor(contentType)

        FoundVideo(
                name = name,
                url = url,
                ext = ext,
                size = size,
                sourceWebPage = sourceWebPage,
                sourceWebsite = sourceWebsite,
                isChunked = isChunked
        ).apply { onFoundVideo(this) }
    }

    private fun getYoutubeVideoTitle(sourcePage: String): String {
        var title = ""
        connect("https://www.youtube.com/oembed?url=$sourcePage&format=json") {
            val json = content
            val jsonObject = JsonParser.parseString(json).asJsonObject
            title = jsonObject.get("title").asString
        }
        return title
    }

    private fun getExtensionFor(contentType: String): String {
        return when (contentType) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/mp2t" -> "ts"
            "audio/webm" -> "webm"
            else -> if (contentType.contains("audio")) "m4a" else "mp4"
        }
    }

    private fun HttpNetwork.Connection.extractM3U8Video(
            title: String,
            sourceWebPage: String
    ) {

        val host = URL(sourceWebPage).host
        if (host.contains("twitter.com") || host.contains("metacafe.com")
                || host.contains("myspace.com")) {

            val name = if (title.isNotBlank()) title else "video"

            var prefix = ""
            var ext = "mp4"
            var sourceWebsite = ""
            when {
                host.contains("twitter.com") -> {
                    prefix = "https://video.twimg.com"
                    ext = "ts"
                    sourceWebsite = "twitter.com"
                }
                host.contains("metacafe.com") -> {
                    val url = urlHandler.url ?: return
                    prefix = "${url.substringBeforeLast("/")}/"
                    sourceWebsite = "metacafe.com"
                    ext = "mp4"
                }
                host.contains("myspace.com") -> {
                    FoundVideo(
                            name = name,
                            url = urlHandler.url ?: return,
                            ext = "ts",
                            size = "0",
                            sourceWebPage = sourceWebPage,
                            sourceWebsite = "myspace.com",
                            isChunked = true
                    ).apply { onFoundVideo(this) }
                    return
                }
            }
            stream?.reader()?.forEachLine {
                if (it.endsWith(".m3u8")) {
                    val url = "$prefix$it"
                    FoundVideo(
                            name = name,
                            url = url,
                            ext = ext,
                            size = "0",
                            sourceWebPage = sourceWebPage,
                            sourceWebsite = sourceWebsite,
                            isChunked = true
                    ).apply { onFoundVideo(this) }
                }
            }
        }
    }

    private fun connect(url: String, block: HttpNetwork.Connection.() -> Unit) =
            try {
                network.open(url).apply(block)
            } catch (e: Exception) {
                Timber.e(e, "Failed connecting to $url")
                null
            }

    private fun onFoundVideo(video: FoundVideo) {
        Timber.i("""
                    FoundVideo:
                        url = ${video.url}
                        name = ${video.name}
                        ext = ${video.ext}
                        size = ${video.size}
                        page = ${video.sourceWebPage}
                        site = ${video.sourceWebsite}
                        chunked = ${if (video.isChunked) "yes" else "no"}                    
                """.trimIndent())

        _foundVideos.add(video)

        viewModelScope.launch(Dispatchers.Main) {
            sendFoundVideo.send(video)
        }
    }

    override fun observeIsAnalyzing(lifecycleOwner: LifecycleOwner, observer: Observer<Boolean>) {
        isAnalyzing.observe(lifecycleOwner, observer)
    }

    override fun receiveForFoundVideo(lifecycleOwner: LifecycleOwner, observer: Observer<FoundVideo>) {
        sendFoundVideo.observeSend(lifecycleOwner, observer)
    }

    override fun selectAll() {
        _foundVideos.forEach { it.isSelected = true }
    }

    override fun unselectAll() {
        _foundVideos.forEach { it.isSelected = false }
    }

    override fun setSelection(index: Int, isSelected: Boolean) {
        _foundVideos[index].isSelected = isSelected
    }
}