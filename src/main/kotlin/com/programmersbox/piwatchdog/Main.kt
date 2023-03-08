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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.stream.Collectors
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days

fun main(args: Array<String>) = runBlocking {
    println("Program arguments: ${args.joinToString()}")
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
    val jarPath: String by option("-j", help = "Path to Jar").required()
    val updateUrl: String by option("-u", help = "Update Url").required()

    override fun run() = runBlocking {
        val versionFile = File("version.txt")
        if (!versionFile.exists()) versionFile.createNewFile()
        var version = versionFile.readText().ifEmpty { "0.0.1" }
        println("Running PiWatchDog version $version")

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
                    println(response)
                    if (checkForUpdate(version, response.version)) {
                        println("Update!")
                        version = response.version
                        versionFile.writeText(response.version)
                        process?.destroy()
                        isServerRunning.emit(false)
                        println("Downloading Server")
                        delay(10000)
                        val httpResponse: HttpResponse = client.get(response.jarUrl) {
                            var prog = 0
                            onDownload { bytesSentTotal, contentLength ->
                                val progress = (bytesSentTotal * 100f / contentLength).roundToInt()
                                if (prog != progress) {
                                    prog = progress
                                    println("Downloading: $progress%")
                                }
                            }
                        }
                        val responseBody: ByteArray = httpResponse.bodyAsChannel().toByteArray()
                        println(responseBody)
                        File(jarPath).writeBytes(responseBody)
                        println("Downloaded")
                        isServerRunning.emit(true)
                    } else {
                        if(process?.isAlive != true) {
                            process?.destroy()
                            isServerRunning.emit(true)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        launch {
            while(true) {
                delay(30000)
                if(process?.isAlive != true) {
                    process?.destroy()
                    isServerRunning.emit(isServerRunning.value)
                }
            }
        }

        isServerRunning.onEach {
            println("Should run? $it")
            if (it) {
                println("File exists? " + File(jarPath).exists())

                process = runtime.exec(arrayOf("java", "-jar", jarPath))

                println("Pid: ${process!!.pid()}")
                process!!.inputStream.bufferedReader().use { r ->
                    var line: String?
                    while (r.readLine().also { l -> line = l } != null) {
                        println(line)
                    }
                }

                println("At end")
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

object RunCommand {
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun runAsync(vararg command: String) = coroutineScope {
        async {
            val process = Runtime.getRuntime().exec(command)
            process.waitFor()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.lines().collect(Collectors.joining("\n"))
        }
    }
}

@Serializable
data class ServerUpdates(
    val version: String,
    val jarUrl: String
)
