package com.malinskiy.marathon.android.adam

import com.google.common.hash.Hashing
import com.google.common.io.Files
import com.malinskiy.adam.AndroidDebugBridgeClient
import com.malinskiy.adam.exception.PullFailedException
import com.malinskiy.adam.exception.PushFailedException
import com.malinskiy.adam.exception.RequestRejectedException
import com.malinskiy.adam.exception.UnsupportedImageProtocolException
import com.malinskiy.adam.exception.UnsupportedSyncProtocolException
import com.malinskiy.adam.request.async.ChanneledLogcatRequest
import com.malinskiy.adam.request.async.LogcatReadMode
import com.malinskiy.adam.request.devices.DeviceState
import com.malinskiy.adam.request.sync.AndroidFile
import com.malinskiy.adam.request.sync.AndroidFileType
import com.malinskiy.adam.request.sync.GetPropRequest
import com.malinskiy.adam.request.sync.InstallRemotePackageRequest
import com.malinskiy.adam.request.sync.ListFilesRequest
import com.malinskiy.adam.request.sync.PullFileRequest
import com.malinskiy.adam.request.sync.PushFileRequest
import com.malinskiy.adam.request.sync.ScreenCaptureRequest
import com.malinskiy.adam.request.sync.ShellCommandRequest
import com.malinskiy.adam.request.sync.UninstallRemotePackageRequest
import com.malinskiy.adam.request.testrunner.TestRunnerRequest
import com.malinskiy.marathon.analytics.internal.pub.Track
import com.malinskiy.marathon.android.ADB_SCREEN_RECORD_TIMEOUT_MILLIS
import com.malinskiy.marathon.android.ADB_SHORT_TIMEOUT_MILLIS
import com.malinskiy.marathon.android.AndroidAppInstaller
import com.malinskiy.marathon.android.AndroidConfiguration
import com.malinskiy.marathon.android.BaseAndroidDevice
import com.malinskiy.marathon.android.VideoConfiguration
import com.malinskiy.marathon.android.adam.log.LogCatMessageParser
import com.malinskiy.marathon.android.adam.screenshot.ImageAdapter
import com.malinskiy.marathon.android.configuration.SerialStrategy
import com.malinskiy.marathon.android.exception.InstallException
import com.malinskiy.marathon.android.exception.TransferException
import com.malinskiy.marathon.android.executor.listeners.line.LineListener
import com.malinskiy.marathon.device.DevicePoolId
import com.malinskiy.marathon.device.NetworkState
import com.malinskiy.marathon.exceptions.DeviceLostException
import com.malinskiy.marathon.execution.Configuration
import com.malinskiy.marathon.execution.TestBatchResults
import com.malinskiy.marathon.execution.progress.ProgressReporter
import com.malinskiy.marathon.test.TestBatch
import com.malinskiy.marathon.time.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.receiveOrNull
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class AdamAndroidDevice(
    private val client: AndroidDebugBridgeClient,
    private val deviceStateTracker: DeviceStateTracker,
    adbSerial: String,
    configuration: AndroidConfiguration,
    track: Track,
    timer: Timer,
    serialStrategy: SerialStrategy
) : BaseAndroidDevice(adbSerial, serialStrategy, configuration, track, timer) {

    override suspend fun setup() {
        super.setup()

        fetchProps()
        logcatChannel = client.execute(
            ChanneledLogcatRequest(
                modes = listOf(LogcatReadMode.long)
            ), serial = adbSerial, scope = this
        )
        async(context = coroutineContext) {
            val parser = LogCatMessageParser()

            while (!logcatChannel.isClosedForReceive) {
                val logPart = logcatChannel.receiveOrNull() ?: continue
                val messages = parser.processLogLines(logPart.lines(), this@AdamAndroidDevice)
                //TODO: replace with Mutex.lock after the removal of ddmlib
                synchronized(logcatListeners) {
                    messages.forEach { msg ->
                        logcatListeners.forEach { listener ->
                            listener.onLine("${msg.timestamp} ${msg.pid}-${msg.tid}/${msg.appName} ${msg.logLevel.priorityLetter}/${msg.tag}: ${msg.message}")
                        }
                    }
                }
            }
        }
    }

    private val dispatcher by lazy {
        newFixedThreadPoolContext(1, "AndroidDevice - execution - $adbSerial")
    }
    private val imageAdapter = ImageAdapter()

    private lateinit var logcatChannel: ReceiveChannel<String>

    override val coroutineContext: CoroutineContext = dispatcher

    private var props: Map<String, String> = emptyMap()

    override suspend fun executeShellCommand(command: String, errorMessage: String): String? {
        return try {
            return client.execute(ShellCommandRequest(command), serial = adbSerial)
        } catch (e: Exception) {
            logger.error(errorMessage, e)
            null
        }
    }

    override suspend fun safeExecuteShellCommand(command: String, errorMessage: String): String? {
        return try {
            return withTimeoutOrNull(ADB_SHORT_TIMEOUT_MILLIS) {
                return@withTimeoutOrNull client.execute(ShellCommandRequest(command), serial = adbSerial)
            }
        } catch (e: Exception) {
            logger.error(errorMessage, e)
            null
        }
    }

    override suspend fun pullFile(remoteFilePath: String, localFilePath: String) {
        var progress: Double = 0.0
        try {
            val channel = client.execute(request = PullFileRequest(remoteFilePath, File(localFilePath)), serial = adbSerial, scope = this)
            while (!channel.isClosedForReceive) {
                progress = channel.receiveOrNull() ?: break
            }
        } catch (e: PullFailedException) {
            throw TransferException("Couldn't pull file $remoteFilePath from device $serialNumber")
        } catch (e: UnsupportedSyncProtocolException) {
            throw TransferException("Device $serialNumber does not support sync: file transfer")
        }

        if (progress != 1.0) {
            throw TransferException("Couldn't pull file $remoteFilePath from device $serialNumber")
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun pushFile(localFilePath: String, remoteFilePath: String, verify: Boolean) {
        val file = File(localFilePath)
        var progress: Double = 0.0

        try {
            val channel = client.execute(PushFileRequest(file, remoteFilePath), serial = adbSerial, scope = this)
            progress = 0.0
            while (!channel.isClosedForReceive && progress < 1.0) {
                progress = channel.receiveOrNull() ?: break
            }
        } catch (e: PushFailedException) {
            throw TransferException(e)
        }

        if (progress != 1.0) {
            throw TransferException("Couldn't push file $localFilePath to device $serialNumber:$remoteFilePath")
        }

        if (verify) {
            val expectedMd5 = Files.asByteSource(File(localFilePath)).hash(Hashing.md5()).toString()
            waitForRemoteFileSync(expectedMd5, remoteFilePath)
        }
    }

    override suspend fun pullFolder(remoteFolderPath: String, localFolderPath: String) {
        if (!File(localFolderPath).isDirectory) {
            throw TransferException("Destination $localFolderPath is not a directory")
        }

        /**
         * Iterate instead of recursion
         */
        val filesToPull = mutableListOf<AndroidFile>()
        var directoriesToTraverse = listOf(remoteFolderPath)

        while (directoriesToTraverse.isNotEmpty()) {
            //We have to use a second collection because we're iterating over directoriesToTraverse later
            val currentDepthDirs = mutableListOf<String>()
            for (dir in directoriesToTraverse) {
                val currentDepthFiles = client.execute(request = ListFilesRequest(dir), serial = adbSerial)

                filesToPull.addAll(currentDepthFiles.filter { it.type == AndroidFileType.REGULAR_FILE })
                currentDepthDirs.addAll(
                    currentDepthFiles.filter { it.type == AndroidFileType.DIRECTORY }
                        .map { it.directory + '/' + it.name }
                )
            }
            directoriesToTraverse = currentDepthDirs
        }

        filesToPull.forEach { file ->
            val relativePathSegments = file.directory.substringAfter(remoteFolderPath).split('\\')
            val absoluteLocalDirectory = StringBuilder().apply {
                append(localFolderPath)
                append(File.separator)
                relativePathSegments.forEach { segment ->
                    append(segment)
                    append(File.separator)
                }
            }.toString()

            val localFileDirectory = File(absoluteLocalDirectory).apply {
                mkdirs()
            }
            val localFile = File(localFileDirectory, file.name)

            pullFile("${file.directory}${File.separator}${file.name}", localFile.absolutePath)
        }
    }

    override suspend fun safeUninstallPackage(appPackage: String, keepData: Boolean): String? {
        return client.execute(UninstallRemotePackageRequest(appPackage, keepData = keepData), serial = adbSerial)
    }

    override suspend fun installPackage(absolutePath: String, reinstall: Boolean, optionalParams: String): String? {
        val file = File(absolutePath)
        val remotePath = "/data/local/tmp/${file.name}"

        try {
            pushFile(absolutePath, remotePath, verify = true)
        } catch (e: TransferException) {
            throw InstallException(e)
        }

        val result = client.execute(
            InstallRemotePackageRequest(
                remotePath,
                reinstall = reinstall,
                extraArgs = optionalParams.split(" ").toList() + " "
            ), serial = adbSerial
        )

        safeExecuteShellCommand("rm $remotePath")
        return result
    }

    override suspend fun getScreenshot(timeout: Long, units: TimeUnit): BufferedImage? {
        return try {
            val rawImage = client.execute(ScreenCaptureRequest(), serial = adbSerial)
            return imageAdapter.convert(rawImage)
        } catch (e: UnsupportedImageProtocolException) {
            logger.warn(e) { "Unable to retrieve screenshot from device $adbSerial" }
            null
        }
    }

    private val logcatListeners = mutableListOf<LineListener>()

    override fun addLogcatListener(listener: LineListener) {
        synchronized(logcatListeners) {
            logcatListeners.add(listener)
        }
    }

    override fun removeLogcatListener(listener: LineListener) {
        synchronized(logcatListeners) {
            logcatListeners.remove(listener)
        }
    }

    override suspend fun safeStartScreenRecorder(
        remoteFilePath: String,
        options: VideoConfiguration
    ) {
        val screenRecorderCommand = options.toScreenRecorderCommand(remoteFilePath)
        try {
            withTimeoutOrNull(ADB_SCREEN_RECORD_TIMEOUT_MILLIS) {
                val output = client.execute(ShellCommandRequest(screenRecorderCommand), serial = adbSerial)
                logger.debug { "screenrecord output:\n $output" }
            }
        } catch (e: CancellationException) {
            //Ignore
        } catch (e: Exception) {
            logger.error("Unable to start screenrecord", e)
        }
    }

    override suspend fun getProperty(name: String, cached: Boolean): String? = when (cached) {
        true -> props[name]
        false -> {
            fetchProps()
            props[name]
        }
    }

    private suspend fun fetchProps() {
        val map = client.execute(GetPropRequest(), serial = adbSerial)
        props = map
    }

    override val networkState: NetworkState = if (healthy) NetworkState.CONNECTED else NetworkState.DISCONNECTED

    override val healthy: Boolean
        get() = when (deviceStateTracker.getState(adbSerial)) {
            DeviceState.DEVICE -> true
            else -> false
        }

    override suspend fun execute(
        configuration: Configuration,
        devicePoolId: DevicePoolId,
        testBatch: TestBatch,
        deferred: CompletableDeferred<TestBatchResults>,
        progressReporter: ProgressReporter
    ) {
        try {
            val deferredResult = async(context = coroutineContext) {
                val listener = createExecutionListeners(configuration, devicePoolId, testBatch, deferred, progressReporter)
                AndroidDeviceTestRunner(this@AdamAndroidDevice).execute(configuration, testBatch, listener)
            }

            deferredResult.await()
        } catch (e: RequestRejectedException) {
            throw DeviceLostException(e)
        }
    }

    override suspend fun prepare(configuration: Configuration) {
        val preparationJob = async(coroutineContext) {
            track.trackDevicePreparing(this@AdamAndroidDevice) {
                AndroidAppInstaller(configuration).prepareInstallation(this@AdamAndroidDevice)
                fileManager.removeRemoteDirectory()
                fileManager.createRemoteDirectory()
                clearLogcat()
            }
        }
        preparationJob.await()
    }

    override fun dispose() {
        dispatcher.close()
    }

    fun executeTestRequest(runnerRequest: TestRunnerRequest): ReceiveChannel<String> {
        return client.execute(runnerRequest, scope = this, serial = adbSerial)
    }
}
