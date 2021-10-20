package manager

import data.*
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import logging.Dashboard
import logging.Logger
import utils.Crypto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by Mihael Valentin Berčič
 * on 27/11/2020 at 17:11
 * using IntelliJ IDEA
 */
class DockerManager(
    private val dht: DistributedHashTable,
    private val crypto: Crypto,
    private val networkManager: NetworkManager,
    private val dashboard: Dashboard,
    private val configuration: Configuration
) {

    private val containerMappings = ConcurrentHashMap<String, String>()

    private val latestStatistics = mutableMapOf<String, ContainerStatistics>()

    init {
        listenForDockerStatistics()
    }

    fun getLatestStatistics(lastBlock: Block): DockerStatistics {
        val containers = latestStatistics.values.filter { System.currentTimeMillis() - it.updated <= 1000 }
            .map { container ->
                val networkIdentification = containerMappings[container.id] ?: container.id
                container.copy(id = networkIdentification)
            }

        return DockerStatistics(crypto.publicKey, containers, lastBlock.slot)
    }

    /** Saves the image of the container([container]) and is stored as either checkpoint or .tar data. */
    private fun saveContainer(container: String): File {
        val arguments = if (configuration.useCriu) arrayOf("-c", container) else arrayOf(container)
        ProcessBuilder("bash", "SaveContainer.sh", *arguments).start().waitFor()
        return File("/tmp/$container.tar")
    }

    fun migrateContainer(migrationPlan: MigrationPlan, block: Block) {
        Logger.info("We have to send container ${migrationPlan.container} to ${migrationPlan.to}")
        dht.searchFor(migrationPlan.to) { receiver ->
            val container = migrationPlan.container
            val startedAt = System.currentTimeMillis()
            val file = saveContainer(containerMappings[container] ?: container)
            val savedAt = System.currentTimeMillis()
            val containerMigration = ContainerMigration(container, block.slot, startedAt, savedAt)
            val encoded = ProtoBuf.encodeToByteArray(containerMigration)
            Socket(receiver.ip, networkManager.listeningPort + 1).use { socket ->
                DataOutputStream(socket.getOutputStream()).apply {
                    writeInt(encoded.size)
                    write(encoded)
                    writeLong(file.length())
                    file.inputStream().use { it.transferTo(this) }
                }
            }
            file.delete()
            latestStatistics.remove(container)
        }
    }

    fun executeMigration(socket: Socket) {
        DataInputStream(socket.getInputStream()).use { dataInputStream ->
            val encodedLength = dataInputStream.readInt()
            val migrationData = dataInputStream.readNBytes(encodedLength)
            val fileLength = dataInputStream.readLong()
            val migrationInformation = ProtoBuf.decodeFromByteArray<ContainerMigration>(migrationData)

            val image = migrationInformation.image
            val migratedContainer = migrationInformation.container

            val outputFile = File("/tmp/$migratedContainer.tar").apply {
                outputStream().use { dataInputStream.transferTo(it) }
            }

            val saveTime = migrationInformation.savedAt - migrationInformation.start
            val transmitDuration = System.currentTimeMillis() - migrationInformation.transmitAt
            val resumeStart = System.currentTimeMillis()
            val arguments = if (configuration.useCriu) arrayOf("-c", migratedContainer, image) else arrayOf(migratedContainer)
            val newContainer = ProcessBuilder("bash", "RunContainer.sh", *arguments)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().use { it.readLine() }
            val resumeDuration = System.currentTimeMillis() - resumeStart
            val elapsed = System.currentTimeMillis() - migrationInformation.start
            val localIp = socket.localSocketAddress.toString()
            val remoteIp = socket.remoteSocketAddress.toString()
            val totalSize = encodedLength + fileLength
            containerMappings[newContainer] = migratedContainer
            containerMappings[migratedContainer] = newContainer
            dashboard.newMigration(localIp, remoteIp, migratedContainer, elapsed, saveTime, transmitDuration, resumeDuration, totalSize, migrationInformation.slot)
            outputFile.delete()
        }
    }

    /** Starts a process of `docker stats` and keeps the [latestStatistics] up to date. */
    private fun listenForDockerStatistics() {
        Thread {
            val process = ProcessBuilder()
                .command("docker", "stats", "--no-trunc", "--format", "{{.ID}} {{.CPUPerc}} {{.MemPerc}} {{.PIDs}}")
                .redirectErrorStream(true)
                .start()

            val buffer = ByteBuffer.allocate(100_000)
            val escapeSequence = byteArrayOf(0x1B, 0x5B, 0x32, 0x4A, 0x1B, 0x5B, 0x48)
            var escapeIndex = 0
            process.inputStream.use { inputStream ->
                while (true) {
                    try {
                        val byte = inputStream.read().toByte()
                        if (byte < 0) break
                        buffer.put(byte)
                        if (byte == escapeSequence[escapeIndex]) escapeIndex++ else escapeIndex = 0
                        if (escapeIndex != escapeSequence.size) continue
                        val length = buffer.position() - escapeSequence.size
                        if (length > 0) String(buffer.array(), 0, length).split("\n").map { line ->
                            if (line.isNotEmpty()) {
                                val fields = line.split(" ")
                                if (fields.none { it == "--" || it.isEmpty() }) {
                                    val containerId = fields[0]
                                    val cpuPercentage = fields[1].trim('%').toDouble()
                                    val memoryPercentage = fields[2].trim('%').toDouble()
                                    val processes = fields[3].toInt()
                                    val container = ContainerStatistics(containerId, cpuPercentage, memoryPercentage, processes)
                                    latestStatistics[containerId] = container
                                }
                            }
                        }
                        buffer.clear()
                        escapeIndex = 0
                    } catch (e: Exception) {
                        buffer.clear()
                        escapeIndex = 0
                        dashboard.reportException(e)
                    }
                }
            }
        }.start()
    }
}