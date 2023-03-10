package com.programmersbox.piwatchdog

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.tongfei.progressbar.ProgressBarBuilder
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days

const val LOG_COLOR = 0x00ff00

fun main(args: Array<String>) = runBlocking {
    println("Program arguments: ${args.joinToString()}".color(LOG_COLOR))
    WatchDog().main(args)
}

fun checkForUpdate(oldVersion: String, newVersion: String): Boolean = try {
    val items = oldVersion.split(".").zip(newVersion.split("."))
    val major = items[0]
    val minor = items[1]
    val patch = items[2]
    /*
     new major > old major
     new major == old major && new minor > old minor
     new major == old major && new minor == old minor && new patch > old patch
     else false
     */
    when {
        major.second.toInt() > major.first.toInt() -> true
        major.second.toInt() == major.first.toInt() && minor.second.toInt() > minor.first.toInt() -> true
        major.second.toInt() == major.first.toInt()
                && minor.second.toInt() == minor.first.toInt()
                && patch.second.toInt() > patch.first.toInt() -> true

        else -> false
    }
} catch (e: Exception) {
    false
}

class WatchDog : CliktCommand() {
    val jarPath by option("-j", help = "Path to Jar").required()
    val updateUrl by option("-u", help = "Update Url").required()

    override fun run() = runBlocking {
        val versionFile = File("version.txt")
        if (!versionFile.exists()) versionFile.createNewFile()
        var version = versionFile.readText().ifEmpty { "0.0.1" }

        val json = Json {
            isLenient = true
            prettyPrint = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
        }

        var process: Process? = null
        val runtime = Runtime.getRuntime()
        val isServerRunning = MutableStateFlow(true)

        val versionCheck = async {
            while (true) {
                delay(1.days.inWholeMilliseconds)
                try {
                    val response = client.get(updateUrl)
                        .bodyAsText()
                        .let { json.decodeFromString<ServerUpdates>(it) }
                    //println(response.toString().color(LOG_COLOR))
                    if (checkForUpdate(version, response.version)) {
                        println("Update!".color(LOG_COLOR))
                        version = response.version
                        versionFile.writeText(response.version)
                        process?.destroy()
                        isServerRunning.emit(false)
                        println("Downloading Server".color(LOG_COLOR))
                        delay(5000)
                        val pb = ProgressBarBuilder()
                            .showSpeed()
                            .setTaskName("Downloading...")
                            .setInitialMax(100)
                            .clearDisplayOnFinish()
                            .build()
                        val httpResponse: HttpResponse = client.get(response.jarUrl) {
                            onDownload { bytesSentTotal, contentLength ->
                                //TODO: See if we can animate a little thing on the screen
                                val progress = (bytesSentTotal * 100f / contentLength).roundToInt()
                                pb.stepTo(progress.toLong())
                            }
                        }
                        pb.close()
                        File(jarPath).writeBytes(httpResponse.bodyAsChannel().toByteArray())
                        delay(5000)
                        println("Downloaded".color(LOG_COLOR))
                        isServerRunning.emit(true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        launch {
            while (true) {
                delay(30000)
                if (process?.isAlive != true) {
                    process?.destroy()
                    isServerRunning.emit(isServerRunning.value)
                }
            }
        }

        isServerRunning.onEach {
            println("Should run? $it".color(LOG_COLOR))
            if (it) {
                println("File exists? ${File(jarPath).exists()}".color(LOG_COLOR))

                process = runtime.exec(arrayOf("java", "-jar", jarPath))

                println("Pid: ${process!!.pid()}".color(LOG_COLOR))
                process!!.inputStream.bufferedReader().use { r ->
                    var line: String?
                    while (r.readLine().also { l -> line = l } != null) {
                        println(line)
                    }
                }

                println("At end".color(LOG_COLOR))
            } else {
                process?.destroy()
            }
        }
            .retry {
                it.printStackTrace()
                true
            }
            .flowOn(Dispatchers.IO)
            .debounce(10000)
            .launchIn(this)

        versionCheck.await()
    }
}

@Serializable
data class ServerUpdates(
    val version: String,
    val jarUrl: String
)

object AnsiColor {
    const val prefix = "\u001B"
    const val RESET = "$prefix[0m"
    private val isCompatible = !System.getProperty("os.name")!!.lowercase().contains("win")
    fun getColor(r: Int, g: Int, b: Int) = "[38;2;$r;$g;$b"
    fun regularColor(r: Int, g: Int, b: Int) = if (isCompatible) "$prefix${getColor(r, g, b)}m" else ""
    fun regularColor(color: Int) = color.valueOf().let { regularColor(it.first, it.second, it.third) }
    fun colorText(s: String, color: Int) = "${regularColor(color)}$s$RESET"
    fun colorText(s: String, r: Int, g: Int, b: Int) = "${regularColor(r, g, b)}$s$RESET"
    private fun Int.valueOf(): Triple<Int, Int, Int> {
        val r = (this shr 16 and 0xff)// / 255.0f
        val g = (this shr 8 and 0xff)// / 255.0f
        val b = (this and 0xff)// / 255.0f
        return Triple(r, g, b)
    }
    private fun Long.valueOf(): Triple<Long, Long, Long> {
        val r = (this shr 16 and 0xff)// / 255.0f
        val g = (this shr 8 and 0xff)// / 255.0f
        val b = (this and 0xff)// / 255.0f
        return Triple(r, g, b)
    }
}

fun String.color(color: Int) = AnsiColor.colorText(this, color)
fun String.color(r: Int, g: Int, b: Int) = AnsiColor.colorText(this, r, g, b)