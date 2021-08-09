package communication

import data.Node
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.apache.commons.codec.digest.DigestUtils
import java.util.*

/**
 * Created by Mihael Valentin Berčič
 * on 18/04/2020 at 16:33
 * using IntelliJ IDEA
 */
@Serializable
data class QueryMessage(val seekingNode: Node, val searchingPublicKey: String)

@Serializable
data class SyncRequest(val node: Node, val fromBlock: Int)

@Serializable
data class InclusionRequest(val currentSlot: Int, val nodePublicKey: String)

@Serializable
data class QueuedMessage<T>(
    val value: Message<T>,
    @Transient val block: (Message<T>) -> Unit = {},
    @Transient val execute: () -> Unit = { block.invoke(value) }
)

@Serializable
class JoinedMessage(val acceptor: Node, val knownNodes: Array<Node>)

/**
 * Message with body of type T.
 *
 * Encapsulation of data that is sent to the client. The data will be verified via the signature and public key upon arrival.
 *
 * @param T Message body class type.
 * @property publicKey public key of the current node.
 * @property signature encryption signature.
 * @property body information message holds.
 * @property timeStamp timestamp when the message was generated.
 * @property encoded returns JSON of the data class.
 * @property encodedBody returns @body as JSON.
 */
@Serializable
class Message<T>(
    val publicKey: String,
    val signature: ByteArray,
    val body: T,
    val timeStamp: Long = System.currentTimeMillis(),
    val uid: String = DigestUtils.sha256Hex(UUID.randomUUID().toString())
)