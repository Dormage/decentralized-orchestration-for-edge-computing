package manager

import blockchain.Block
import blockchain.BlockVote
import io.javalin.http.Context
import logging.Logger
import messages.NewBlockMessageBody
import messages.RequestBlocksMessageBody
import messages.ResponseBlocksMessageBody
import org.apache.commons.codec.digest.DigestUtils
import state.ChainTask
import state.SlotDuty
import utils.getMessage
import java.math.BigInteger
import kotlin.random.Random

/**
 * Created by Mihael Valentin Berčič
 * on 25/09/2020 at 16:58
 * using IntelliJ IDEA
 */
class ChainManager(private val applicationManager: ApplicationManager) {

    val lastBlock: Block? get() = chain.lastOrNull()
    val isChainEmpty: Boolean get() = chain.isEmpty()

    val votes = mutableListOf<BlockVote>()
    val chain = mutableListOf<Block>()

    private val vdf by lazy { applicationManager.kotlinVDF }
    private val crypto by lazy { applicationManager.crypto }
    private val dht by lazy { applicationManager.dhtManager }
    private val timeManager by lazy { applicationManager.timeManager }
    private val nodeNetwork by lazy { applicationManager.networkManager.nodeNetwork }
    private val configuration by lazy { applicationManager.configuration }
    private val blockProducer by lazy { applicationManager.blockProducer }
    private val validatorManager by lazy { applicationManager.validatorManager }
    private val state by lazy { applicationManager.currentState }


    fun addBlock(block: Block) {
        if (++state.currentSlot == configuration.slotCount) {
            state.currentEpoch++
            state.currentSlot = 0
            Logger.debug("Moved to next epoch!")
        }

        Logger.chain("Added block with [epoch][slot] => [${block.epoch}][${block.slot}] ")

        applicationManager.apply {
            validatorSetChanges.clear()
            updateValidatorSet(block)
        }

        chain.add(block)
        votes.clear()

        val nextTask = calculateNextDuties(block)

        when (nextTask.myTask) {
            SlotDuty.PRODUCER -> {
                val newBlock = blockProducer.createBlock(block)
                val message = nodeNetwork.createNewBlockMessage(newBlock)

                timeManager.runAfter(1000) { nodeNetwork.broadcast("/voteRequest", message) }

                timeManager.runAfter(configuration.slotDuration * 2 / 3) {
                    newBlock.vdfProof = votes[0].vdfProof
                    val votesAmount = votes.size
                    val broadcastMessage = nodeNetwork.createNewBlockMessage(newBlock)

                    Logger.debug("We got $votesAmount votes and we're broadcasting...")

                    nodeNetwork.broadcast("/block", broadcastMessage)
                    addBlock(newBlock)
                }
            }
            SlotDuty.COMMITTEE, SlotDuty.VALIDATOR -> Unit
        }
    }

    fun runVDF(onBlock: Block) = vdf.findProof(onBlock.difficulty, onBlock.hash, onBlock.epoch)

    fun isVDFCorrect(proof: String) = chain.lastOrNull()?.let { vdf.verifyProof(it.difficulty, it.hash, proof) }
            ?: false

    fun requestSync() {
        val from = state.currentEpoch * configuration.slotCount + state.currentSlot
        Logger.info("Requesting new blocks from $from")
        val message = nodeNetwork.createRequestBlocksMessage(from)
        nodeNetwork.sendMessageToRandomNodes("/syncRequest", 1, message)
    }

    fun syncRequestReceived(context: Context) {
        val message = context.getMessage<RequestBlocksMessageBody>()
        val blockMessage = message.body

        val blocks = chain.drop(blockMessage.epoch)
        val responseBlocksMessageBody = nodeNetwork.createResponseBlocksMessage(blocks)
        blockMessage.node.sendMessage("/syncReply", responseBlocksMessageBody)
    }

    fun syncReplyReceived(context: Context) {
        val message = context.getMessage<ResponseBlocksMessageBody>()
        val body = message.body
        val blocks = body.blocks

        Logger.info("We have ${blocks.size} blocks to sync...")
        blocks.forEach { block ->
            addBlock(block)
            state.currentSlot = block.slot
            state.currentEpoch = block.epoch
        }
        validatorManager.requestInclusion()
        Logger.info("Syncing finished...")
    }

    fun blockReceived(context: Context) {
        val message = context.getMessage<NewBlockMessageBody>()
        val body = message.body
        val newBlock = body.block

        nodeNetwork.broadcast("/block", message)

        // Logger.chain("Block received...")
        if (newBlock.precedentHash == lastBlock?.hash ?: "") addBlock(newBlock)
        else requestSync()
    }

    private fun calculateNextDuties(block: Block): ChainTask {
        val proof = block.vdfProof
        val hex = DigestUtils.sha256Hex(proof)
        val seed = BigInteger(hex, 16).remainder(Long.MAX_VALUE.toBigInteger()).toLong()
        val random = Random(seed)
        val ourKey = crypto.publicKey

        val validatorSetCopy = applicationManager.currentValidators.toMutableList().shuffled(random).toMutableList()
        val blockProducerNode = validatorSetCopy[0].apply { validatorSetCopy.remove(this) }
        val committee = validatorSetCopy.take(configuration.committeeSize)

        Logger.error("Block producer is: ${blockProducerNode.drop(30).take(15)}")
        val ourRole = when {
            blockProducerNode == ourKey -> SlotDuty.PRODUCER
            committee.contains(ourKey) -> SlotDuty.COMMITTEE
            else -> SlotDuty.VALIDATOR
        }

        Logger.debug("Next task: $ourRole")

        if (ourRole == SlotDuty.PRODUCER) committee.forEach(dht::sendSearchQuery)
        return ChainTask(ourRole, committee)
    }

    fun voteReceived(context: Context) {
        val message = context.getMessage<BlockVote>()
        votes.add(message.body)
    }

}