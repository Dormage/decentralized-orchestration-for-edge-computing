package manager

import chain.BlockProducer
import data.*
import logging.Logger
import org.apache.commons.codec.digest.DigestUtils
import utils.runAfter
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random


/**
 * Created by Mihael Valentin Berčič
 * on 25/09/2020 at 16:58
 * using IntelliJ IDEA
 */
class ChainManager(private val networkManager: NetworkManager) {

    val isChainEmpty: Boolean get() = chain.isEmpty()

    private var isIncluded: Boolean = false
    private val crypto = networkManager.crypto
    private val configuration = networkManager.configuration
    private val currentState = networkManager.currentState
    private val vdf = networkManager.vdf
    private val dht = networkManager.dht
    private val dashboard = networkManager.dashboard
    private val informationManager = networkManager.informationManager
    private val knownNodes = networkManager.knownNodes

    private val votes = ConcurrentHashMap<String, MutableList<VoteInformation>>()
    private val chain = mutableListOf<Block>()

    private var isSynced = false

    val blockProducer = BlockProducer(crypto, configuration, currentState)
    val validatorManager = ValidatorManager(networkManager, this)


    /**
     * Adds the specified block to the chain and calculates our task for the next slot.
     *
     * @param block
     */
    fun addBlock(block: Block, fromSync: Boolean = false) {
        currentState.apply {
            block.validatorChanges.forEach { (publicKey, change) -> if (change) currentValidators.add(publicKey) else currentValidators.remove(publicKey) }
            currentSlot = block.slot
            currentEpoch = block.epoch
        }

        chain.add(block)
        votes.remove(block.hash)
        if (!isIncluded && block.validatorChanges[crypto.publicKey] == true) isIncluded = true

        val nextTask = calculateNextTask(block, !fromSync)
        val textColor = when (nextTask.myTask) {
            SlotDuty.PRODUCER -> Logger.green
            SlotDuty.COMMITTEE -> Logger.blue
            SlotDuty.VALIDATOR -> Logger.white
        }

        Logger.debug("Clearing statistics!")
        informationManager.latestNetworkStatistics.clear()

        Logger.chain("Added block with [epoch][slot][votes] => [${block.epoch}][${block.slot}][${Logger.green}${block.votes}${Logger.reset}] Next task: $textColor${nextTask.myTask}")
        dashboard.newRole(nextTask, DigestUtils.sha256Hex(crypto.publicKey), currentState);
        if (networkManager.isTrustedNode) dashboard.newBlockProduced(block)

        when (nextTask.myTask) {
            SlotDuty.PRODUCER -> {
                val vdfProof = vdf.findProof(block.difficulty, block.hash)
                if (++currentState.currentSlot == configuration.slotCount) {
                    currentState.currentEpoch++
                    currentState.currentSlot = 0
                }
                val newBlock = blockProducer.createBlock(block, vdfProof)
                val voteRequest = VoteRequest(newBlock, networkManager.ourNode)

                runAfter(500) {
                    val message = networkManager.generateMessage(voteRequest)
                    nextTask.committee.forEach { key -> networkManager.knownNodes[key]?.sendMessage(EndPoint.OnVoteRequest, message) }
                }

                runAfter(configuration.slotDuration * 2 / 3) {

                    val thisBlockVotes = votes[newBlock.hash]
                    val votesAmount = thisBlockVotes?.size ?: 0
                    val broadcastMessage = networkManager.generateMessage(newBlock)

                    Logger.info("--------- Information ------------")
                    Logger.info("DockerStatistics count: ${informationManager.latestNetworkStatistics.size}")
                    Logger.info("------------- END ----------------")

                    newBlock.votes = votesAmount
                    networkManager.broadcast(EndPoint.BlockReceived, broadcastMessage)
                    addBlock(newBlock)
                    newBlock.validatorChanges.forEach { (key, _) -> currentState.inclusionChanges.remove(key) }
                }
            }
            SlotDuty.COMMITTEE, SlotDuty.VALIDATOR -> if (!fromSync) informationManager.prepareForStatistics(nextTask.blockProducer, currentState.currentValidators, block)
        }
    }

    /**
     * Request blocks from a random known node needed for synchronization.
     *
     */
    private fun requestSync() {
        isSynced = false
        val from = currentState.currentEpoch * configuration.slotCount + currentState.currentSlot
        val message = networkManager.generateMessage(from)
        Logger.trace("Requesting new blocks from $from")
        networkManager.sendMessageToRandomNodes(EndPoint.SyncRequest, 1, message)
    }

    /**
     * After synchronization request has been received, we send back the blocks node has asked us for.
     *
     * @param body Web request body.
     */
    fun syncRequestReceived(message: Message<Int>) {
        val blocks = chain.drop(message.body)
        val responseBlocksMessageBody = networkManager.generateMessage(blocks)
        knownNodes[message.publicKey]?.sendMessage(EndPoint.SyncReply, responseBlocksMessageBody)
    }

    /**
     * Received blocks for chain synchronization.
     *
     * @param context Web request context.
     */
    fun syncReplyReceived(message: Message<Array<Block>>) {
        val blocks = message.body
        Logger.info("We have ${blocks.size} blocks to sync...")
        blocks.forEach { block ->
            addBlock(block, true)
            currentState.currentSlot = block.slot
            currentState.currentEpoch = block.epoch
        }
        isSynced = true
        Logger.info("Syncing finished...")
    }

    fun blockReceived(message: Message<Block>) {
        val newBlock = message.body
        networkManager.broadcast(EndPoint.BlockReceived, message)

        val lastBlock = chain.lastOrNull()
        val lastHash = lastBlock?.hash ?: ""
        if (newBlock.precedentHash == lastHash) addBlock(newBlock)
        else {
            if (lastBlock != null) Logger.error("\n[${newBlock.epoch}][${newBlock.slot}]\nPrecedent: ${newBlock.precedentHash}\nLast: $lastHash\nNew: ${newBlock.hash}")
            if (newBlock.hash != lastHash) requestSync()
        }
    }

    private fun calculateNextTask(block: Block, askForInclusion: Boolean = true): ChainTask {
        val seed = block.getRandomSeed
        val random = Random(seed)
        val ourKey = crypto.publicKey

        val validatorSetCopy = currentState.currentValidators.toMutableList().shuffled(random).toMutableList()
        val blockProducerNode = validatorSetCopy[0].apply { validatorSetCopy.remove(this) }
        val committee = validatorSetCopy.take(configuration.committeeSize)

        if (askForInclusion && !isIncluded) validatorManager.requestInclusion(blockProducerNode)

        val ourRole = when {
            blockProducerNode == ourKey -> SlotDuty.PRODUCER
            committee.contains(ourKey) -> SlotDuty.COMMITTEE
            else -> SlotDuty.VALIDATOR
        }

        if (ourRole == SlotDuty.PRODUCER) committee.forEach(dht::searchFor)
        return ChainTask(ourRole, blockProducerNode, committee)
    }

    fun voteReceived(message: Message<BlockVote>) {
        val blockVote = message.body
        votes.getOrPut(blockVote.blockHash) { mutableListOf() }.add(VoteInformation(message.publicKey))
    }

}