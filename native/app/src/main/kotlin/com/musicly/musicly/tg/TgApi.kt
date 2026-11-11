package com.musicly.musicly.tg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Logging in as the user's own Telegram account (not a bot), so songs can be
 * streamed from every channel and group they are in. The real implementation
 * (TdBackend) uses TDLib; screens only see this contract, so they can be tested
 * with a fake.
 */
sealed interface TgAuth {
    /** Not set up (or logged out). */
    data object Off : TgAuth

    /** TDLib is starting. */
    data object Starting : TgAuth

    /** Waiting for the phone number, e.g. "+251911234567". [error] is the last failure. */
    data class WaitPhone(val error: String? = null) : TgAuth

    /** Telegram sent a code. [where] says how ("Telegram app", "SMS", "phone call"). */
    data class WaitCode(val phone: String, val where: String, val length: Int = 5, val resendInSec: Int? = null, val error: String? = null) : TgAuth

    /** The account has 2-step verification. */
    data class WaitPassword(val hint: String? = null, val error: String? = null) : TgAuth

    /** Logged in. */
    data class Ready(val name: String, val phone: String? = null) : TgAuth

    /** Something went wrong that the user can't fix by retyping (network, bad app key...). */
    data class Failed(val message: String) : TgAuth
}

enum class TgChatKind { Channel, Group }

/** A channel or group the user is in. */
data class TgChat(val id: Long, val title: String, val kind: TgChatKind, val members: Int? = null, val photoPath: String? = null)

/** One audio message. [messageId] and [chatId] identify it for good; file ids are looked up when playing. */
data class TgSong(
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val mime: String,
    val sizeBytes: Long,
    val fileName: String = "",
    val dateSec: Long = 0,
)

interface TgBackend {
    val auth: StateFlow<TgAuth>

    /** Starts TDLib with the user's own app key (api_id / api_hash from my.telegram.org). Safe to call again. */
    suspend fun start(apiId: Int, apiHash: String)

    suspend fun submitPhone(phone: String)
    suspend fun submitCode(code: String)
    suspend fun submitPassword(password: String)
    suspend fun resendCode()

    /** Logs out and deletes the local TDLib database. */
    suspend fun logOut()

    /** Channels and groups the user is in (not private chats), most recently active first. */
    suspend fun chats(): List<TgChat>

    /**
     * Audio messages of one chat, newest first, starting below [fromMessageId]
     * (0 = from the newest). Returns at most [limit] songs, fewer at the end of the history.
     * Must be gentle: one request at a time, backing off when Telegram says to wait.
     */
    suspend fun audioPage(chatId: Long, fromMessageId: Long, limit: Int = 50): List<TgSong>

    /** New audio posted while the app runs, in any chat (the caller filters to the chats the user chose). */
    fun newAudio(): Flow<TgSong>

    /** The chat's small round picture as a local file path, or null. */
    suspend fun chatPhoto(chatId: Long): String?

    /**
     * Local path of a file that is being downloaded for playing, once its first
     * bytes exist. Used by the player's data source (see TdDataSource).
     */
    suspend fun openAudio(chatId: Long, messageId: Long): TgOpenFile
}

/** An audio file being downloaded by TDLib: read [path] up to the bytes downloaded so far. */
interface TgOpenFile {
    val path: String
    val totalBytes: Long

    /** Bytes available from the start of the file right now. */
    fun available(): Long

    /** Suspends until at least [upTo] bytes exist (or the download ends); returns how many exist. Asks TDLib to prioritise the range around [offset]. */
    suspend fun await(offset: Long, upTo: Long): Long

    fun close()
}
