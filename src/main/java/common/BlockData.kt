package common

import org.apache.commons.codec.digest.DigestUtils

/*
BLOCK { header, epoch, slot, previousHash, currentHash, timeStamp, signature, body }

 */

data class Block(val epoch: Int,
                 val slot: Int,
                 val height: Int,
                 val difficulty: Int,
                 val timestamp: Long,
                 val committeeIndex: Int,
                 val precedentHash: String = "",
                 val validatorChanges: Map<Boolean, String> = emptyMap(),
                 val hash: String = DigestUtils.sha256Hex("$epoch$slot$height$difficulty$timestamp$committeeIndex$precedentHash"))

/*
data class BlockData(
        val previousBlockHash: String? = null,
        val height: Int = 0,
        val ticket: Int = 0,
        val difficulty: Int = 0,
        val vdfProof: String? = null,
        val blockProducer: String? = null,
        val timestamp: Long? = 0,
        val consensusNodes: List<String> = emptyList(),
        val hash: String = DigestUtils.sha256Hex(previousBlockHash + height + ticket + difficulty + vdfProof + blockProducer + timestamp + consensusNodes)
) {
    companion object {

        fun genesisBlock(block_producer: String, difficulty: Int): BlockData = BlockData(
                blockProducer = block_producer,
                difficulty = difficulty,
                consensusNodes = mutableListOf(block_producer)
        )

        fun forgeNewBlock(previous_block: BlockData, vdfProof: String, publicKey: String, inclusionRequests: List<String>): BlockData = BlockData(
                vdfProof = vdfProof,
                height = previous_block.height + 1,
                difficulty = previous_block.difficulty,//TODO: Difficulty adjustment algorithm
                blockProducer = publicKey,
                previousBlockHash = previous_block.hash,
                consensusNodes = (previous_block.consensusNodes.plus(inclusionRequests).plus(publicKey)).distinct()
        )
    }
}
*/

