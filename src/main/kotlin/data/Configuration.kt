package data

import kotlinx.serialization.Serializable

/**
 * Created by Mihael Valentin Berčič
 * on 27/03/2020 at 12:11
 * using IntelliJ IDEA
 */
@Serializable
data class Configuration(
    val trustedNodeIP: String,
    val trustedNodePort: Int,
    val maxNodes: Int,
    val keystorePath: String,
    val slotDuration: Long,
    val broadcastSpreadPercentage: Int,
    val initialDifficulty: Int,
    val committeeSize: Int,
    val influxUrl: String,
    val influxUsername: String,
    val influxPassword: String,
    val dashboardEnabled: Boolean,
    val loggingEnabled: Boolean,
    val trustedLoggingEnabled: Boolean,
    val historyMinuteClearance: Int,
    val historyCleaningFrequency: Long,
    val nodesPerCluster: Int,
    val maxIterations: Int,
    val packetSplitSize: Int,
    val useCriu: Boolean
)