package chain

import data.Block
import data.Configuration
import data.State
import org.apache.commons.codec.digest.DigestUtils
import utils.Crypto

/**
 * Created by Mihael Valentin Berčič
 * on 24/09/2020 at 14:08
 * using IntelliJ IDEA
 */

class BlockProducer(private val crypto: Crypto, private val configuration: Configuration, private val currentState: State) {

    private val initialDifficulty = configuration.initialDifficulty
    private val currentTime: Long get() = System.currentTimeMillis()

    fun genesisBlock(vdfProof: String): Block = Block(
            epoch = 0,
            slot = 0,
            difficulty = initialDifficulty,
            timestamp = currentTime,
            committeeIndex = 0,
            votes = 0,
            blockProducer = DigestUtils.sha256Hex(crypto.publicKey),
            validatorChanges = currentState.inclusionChanges.toMap(),
            vdfProof = vdfProof
    )

    fun createBlock(previousBlock: Block, vdfProof: String = "", votes: Int = 0): Block = Block(
            epoch = currentState.currentEpoch,
            slot = currentState.currentSlot,
            difficulty = initialDifficulty,
            timestamp = currentTime,
            committeeIndex = currentState.committeeIndex,
            vdfProof = vdfProof,
            votes = votes,
            blockProducer = DigestUtils.sha256Hex(crypto.publicKey),
            validatorChanges = currentState.inclusionChanges.toMap(),
            precedentHash = previousBlock.hash
    )

    fun adjustDifficulty(previousBlock: Block): Int {
        return 10000

        val deltaT: Long = System.currentTimeMillis() - previousBlock.timestamp
        val ratio: Double = deltaT / configuration.slotDuration.toDouble()
        var difficulty: Double = 0.0
        when {
            ratio > 0 -> {
                difficulty = previousBlock.difficulty + (previousBlock.difficulty * (1 - ratio))
                if (difficulty <= 0) {
                    return 100
                }
                return difficulty.toInt()
            }
            ratio < 0 -> {
                difficulty = previousBlock.difficulty - (previousBlock.difficulty * (1 - ratio))
                if (difficulty <= 0) {
                    return 100
                }
                return difficulty.toInt()
            }
            else -> {
                return previousBlock.difficulty
            }
        }
    }
}