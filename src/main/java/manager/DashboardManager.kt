package manager

import data.*
import logging.Logger
import org.apache.commons.codec.digest.DigestUtils
import org.influxdb.InfluxDB
import org.influxdb.InfluxDBFactory
import org.influxdb.dto.Point
import org.influxdb.dto.Query
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit


private lateinit var influxDB: InfluxDB
private lateinit var mysql: Connection;

class DashboardManager(private val configuration: Configuration) {

    private val queue = LinkedBlockingQueue<Point>()

    private fun formatTime(millis: Long): String {
        val timeDifference = millis / 1000;
        val h = timeDifference / (3600)
        val m = (timeDifference - (h * 3600)) / 60
        val s = timeDifference - (h * 3600) - m * 60

        return String.format("%02d:%02d:%02d", h, m, s)
    }

    init {
        if (configuration.dashboardEnabled) {
            influxDB = InfluxDBFactory.connect(configuration.influxUrl, configuration.influxUsername, configuration.influxPassword)
            influxDB.query(Query("CREATE DATABASE PROD"));
            influxDB.setDatabase("PROD")
            //influxDB.setLogLevel(InfluxDB.LogLevel.FULL)
            influxDB.enableBatch(2000, 500, TimeUnit.MILLISECONDS);
            Thread {
                while (true) queue.take().apply {
                    influxDB.write(this)
                }
            }.start()
            if (influxDB.ping().isGood) Logger.info("InfluxDB connection successful")


            //mysql
            mysql = DriverManager.getConnection(
                "jdbc:mysql://sensors.innorenew.eu:3306/grafana",
                configuration.mysqlUser,
                configuration.mysqlPassword
            );
            val statement: Statement = mysql.createStatement();
            statement.executeUpdate("TRUNCATE network")

        } else Logger.info("Dashboard disabled")

    }

    /**
     * Reports each containers' statistics back to our Grafana dashboard.
     *
     * @param statistics Docker statistics that are reported by all representers of clusters.
     */
    fun reportStatistics(statistics: List<DockerStatistics>, currentState: State) {
        return
        for (measurement in statistics) {
            val publicKey = DigestUtils.sha256Hex(measurement.publicKey)
            for (container in measurement.containers) {
                val point = Point.measurement("containers").apply {
                    addField("nodeId", publicKey)
                    addField("containerId", container.id)
                    addField("cpu", container.cpuUsage)
                    addField("memory", container.memoryUsage)
                    addField("epoch", currentState.epoch)
                    addField("slot", currentState.slot)
                }.build()
                queue.add(point)
            }
        }
    }

    fun newBlockProduced(blockData: Block, knownNodesSize: Int, validatorSize: Int) {
        if (!configuration.dashboardEnabled) return
        val point = Point.measurement("block").apply {
            addField("created", formatTime(blockData.timestamp))
            addField("knownSize", knownNodesSize)
            addField("validatorSet", validatorSize)
            addField("slot", blockData.slot)
            addField("difficulty", blockData.difficulty)
            addField("timestamp", blockData.timestamp)
            addField("committeeIndex", blockData.committeeIndex)
            addField("blockProducer", blockData.blockProducer)
            addField("previousHash", blockData.precedentHash)
            addField("hash", blockData.hash)
            addField("votes", blockData.votes)

        }.build()
        queue.add(point)
    }

    fun newVote(vote: BlockVote, publicKey: String) {
        if (!configuration.dashboardEnabled) return
        // Logger.debug("Sending attestation: ${DigestUtils.sha256Hex(vote.signature)} to Influx")
        val point = Point.measurement("attestations").apply {
            addField("blockHash", vote.blockHash)
            addField("signature", DigestUtils.sha256Hex(vote.signature))
            addField("committeeMember", publicKey)
        }.build()

        queue.add(point)
    }

    fun newRole(chainTask: ChainTask, publicKey: String, slot: Int) {
        if (!configuration.dashboardEnabled) return
        // Logger.debug("Sending new chain task : ${chainTask.myTask}")
        val point = Point.measurement("chainTask").apply {
            time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            addField("nodeId", publicKey)
            addField("task", chainTask.myTask.toString())
            addField("slot", slot)
        }.build()
        queue.add(point)
    }

    fun logQueue(queueSize: Int, publicKey: String) {
        if (!configuration.dashboardEnabled) return
        val point = Point.measurement("queueSize").apply {
            addField("nodeId", publicKey)
            addField("queueSize", queueSize)
        }.build()
        queue.add(point)
    }

    fun newMigration(receiver: String, publicKey: String, containerId: String, duration: Long, currentState: State) {
        if (!configuration.dashboardEnabled) return
        val point = Point.measurement("migration").apply {
            addField("from", publicKey)
            addField("to", receiver)
            addField("epoch", currentState.epoch)
            addField("slot", currentState.slot)
            addField("containerId", containerId)
            addField("duration", duration)
        }.build()
        queue.add(point)
    }

    /*
        fun logCluster(epoch: Int, publicKey: String, clusterRepresentative: String) {
            if (!configuration.dashboardEnabled) return
            // Logger.info("${DigestUtils.sha256Hex(publicKey)} -> ${DigestUtils.sha256Hex(clusterRepresentative)}")
            val point = Point.measurement("networkClusters")
                    .addField("epoch", epoch)
                    .addField("nodeId", DigestUtils.sha256Hex(publicKey))
                    .addField("clusterRepresentative", DigestUtils.sha256Hex(clusterRepresentative)).build()
            queue.add(point)
        }
    */

    fun reportException(e: Exception) {
        val point = Point.measurement("exceptions")
            .addField("cause", e.toString())
            .addField("message", e.message)
            .addField("trace", e.stackTrace.joinToString("\n"))
            .build()
        queue.add(point)
    }

    fun requestedInclusion(from: String, producer: String, slot: Int) {
        if (!configuration.dashboardEnabled) return
        val point = Point.measurement("inclusion")
            .addField("from", from)
            .addField("producer", producer)
            .addField("slot", slot).build()
        queue.add(point)
    }

    fun scheduledTimer(task: SlotDuty, state: State) {
        if (!configuration.dashboardEnabled) return
        val point = Point.measurement("timers")
            .addField("task", task.name)
            .addField("index", state.epoch * configuration.slotCount + state.slot)
            .build()
        queue.add(point)
    }

    fun logCluster(epoch: Int, publicKey: String, clusterRepresentative: String) {
        return
        val statement: PreparedStatement = mysql.prepareStatement("INSERT INTO network (source, target) values (?,?) ON DUPLICATE KEY UPDATE target = VALUES(target)");
        statement.setString(1, DigestUtils.sha256Hex(publicKey));
        statement.setString(2, DigestUtils.sha256Hex(clusterRepresentative))
        statement.executeUpdate();
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("dd. MM | HH:mm:ss.SSS")


    fun sentMessage(uid: String, nodeId: String) {
        val point = Point.measurement("messages")
            .addField("message", uid)
            .addField("node", nodeId)
            .addField("type", "SENT")
            .addField("timestamp", LocalDateTime.now().format(timeFormatter))
            .build()
        queue.add(point)
    }

    fun receivedMessage(uid: String, nodeId: String) {
        val point = Point.measurement("messages")
            .addField("message", uid)
            .addField("node", nodeId)
            .addField("type", "RECEIVED")
            .addField("timestamp", LocalDateTime.now().format(timeFormatter))
            .build()
        queue.add(point)
    }
}


