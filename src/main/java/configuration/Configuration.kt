package configuration

data class Configuration(
        val bootstrapNode: String,
        val trustedNodeIP: String,
        val trustedNodePort: Int,
        val listeningPort: Int,
        val maxNodes: Int,
        val keystorePath: String,
        val epochDuration: Long,
        val broadcastSpread: Int,
        val initialDifficulty: Int,
        val validatorsCount: Int,
        val committeeSize: Int,
        val slotCount: Int
) {
    val trustedHttpAddress: String get() = "http://$trustedNodeIP:$trustedNodePort"
}