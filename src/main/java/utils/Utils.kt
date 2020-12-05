package utils

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import data.Message
import data.NetworkRequestType
import io.javalin.http.Context
import logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.concurrent.schedule

/**
 * Created by Mihael Berčič
 * on 26/03/2020 14:45
 * using IntelliJ IDEA
 */

class Utils {

    companion object {

        val gson = GsonBuilder().setPrettyPrinting().create()

        fun <T> sendMessageTo(url: String, path: String = "/", message: Message<T>, type: NetworkRequestType = NetworkRequestType.POST): Pair<Int, String> = urlRequest(type, "$url$path", message.asJson) {
            this.addRequestProperty("hex", message.hex)
        }

        private fun urlRequest(type: NetworkRequestType, url: String, body: String = "", customBlock: HttpURLConnection.() -> Unit = {}): Pair<Int, String> {
            val connection = (URL(url).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = type.name
                connection.apply(customBlock) // Customization
                connection.doOutput = body.isNotEmpty()
                connection.doInput = true
                connection.connectTimeout = 1000
                connection.connect()
                if (body.isNotEmpty()) connection.outputStream.bufferedWriter().use {
                    it.write(body)
                    it.close()
                }
                connection.disconnect()
                return connection.responseCode to connection.responseMessage
            } catch (e: Exception) {
                Logger.error("URL error to $url $e")
            } finally {
                connection.disconnect()
            }
            return 0 to "FUCKTWAT"
        }

        /**
         * Returns the file's contents specified with path
         *
         * @param patḣ
         * @return File's contents
         */
        fun readFile(path: String): String = File(path).readText()
    }

}
// TODO Comment
infix fun String.levenshteinDistance(rhss: String): Int {
    val lhs: CharSequence = this
    val rhs: CharSequence = rhss
    val len0 = lhs.length + 1
    val len1 = rhs.length + 1
    var cost = IntArray(len0)
    var newCost = IntArray(len0)

    for (i in 0 until len0) cost[i] = i
    for (j in 1 until len1) {
        newCost[0] = j
        for (i in 1 until len0) {
            val match = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
            val replaceCost = cost[i - 1] + match
            val insertCost = cost[i] + 1
            val deleteCost = newCost[i - 1] + 1
            newCost[i] = insertCost.coerceAtMost(deleteCost).coerceAtMost(replaceCost)
        }
        val swap = cost
        cost = newCost
        newCost = swap
    }
    return cost[len0 - 1]
}

/**
 * Runs the provided timer task after a specific amount of milliseconds ran.
 *
 * @param delay Time to delay in milliseconds.
 * @param block Lambda to execute after the delay.
 */
fun runAfter(delay: Long, block: () -> Unit) = Timer().schedule(delay) { block.invoke() }

/**
 * Parses the message with body of type T from the http request using gson (if possible).
 *
 * @param T Message's body type
 * @return Message with body type of T
 */
inline fun <reified T> Context.getMessage(): Message<T> = Utils.gson.fromJson<Message<T>>(body(), TypeToken.getParameterized(Message::class.java, T::class.java).type)