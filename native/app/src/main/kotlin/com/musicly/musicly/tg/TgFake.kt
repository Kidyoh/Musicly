package com.musicly.musicly.tg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A pretend Telegram account for tests and screenshots. [setAuth] jumps to any
 * login step; the calls move through the steps like the real thing (the code
 * is [goodCode], the 2-step password is [goodPassword]).
 */
class TgFake(
    initial: TgAuth = TgAuth.Off,
    private val goodCode: String = "12345",
    private val goodPassword: String = "musicly",
    private val hasPassword: Boolean = true,
    /** Songs in every chat (default: a few dozen to a couple of hundred, depending on the chat). */
    private val songsPerChat: Int? = null,
) : TgBackend {
    private var newer = 0

    private val state = MutableStateFlow(initial)
    override val auth: StateFlow<TgAuth> = state.asStateFlow()

    private val incoming = MutableSharedFlow<TgSong>(extraBufferCapacity = 16)

    /** What the app asked for, in order ("start", "phone:+251…", "code", "page:<chat>:<from>"…). */
    val calls = mutableListOf<String>()
    var startedWith: Pair<Int, String>? = null
        private set

    fun setAuth(a: TgAuth) {
        state.value = a
    }

    /** Adds [n] songs newer than everything so far, to every chat (as if posted while the app was closed). */
    fun addNewer(n: Int) {
        newer += n
    }

    private fun songs(chatId: Long): List<TgSong> {
        val old = songsOf(chatId, songsPerChat ?: totalSongs(chatId))
        val fresh = (newer downTo 1).map { j ->
            old.first().copy(messageId = 5000L + j * 3, title = "New song $j", artist = "New Artist")
        }
        return fresh + old
    }

    /** Pretends a new song was just posted. */
    fun post(song: TgSong) {
        incoming.tryEmit(song)
    }

    override suspend fun start(apiId: Int, apiHash: String) {
        calls += "start"
        startedWith = apiId to apiHash
        if (state.value is TgAuth.Off || state.value is TgAuth.Failed) state.value = TgAuth.WaitPhone()
    }

    override suspend fun submitPhone(phone: String) {
        calls += "phone:$phone"
        state.value = if (phone.trim().length < 6) TgAuth.WaitPhone("That phone number doesn't look right.")
        else TgAuth.WaitCode(phone.trim(), "Telegram app", resendInSec = 30)
    }

    override suspend fun submitCode(code: String) {
        calls += "code:$code"
        val s = state.value as? TgAuth.WaitCode ?: return
        state.value = when {
            code != goodCode -> s.copy(error = "That code isn't right. Check it and try again.")
            hasPassword -> TgAuth.WaitPassword("your first pet")
            else -> TgAuth.Ready("Mia Tesfaye", s.phone)
        }
    }

    override suspend fun submitPassword(password: String) {
        calls += "password"
        state.value = if (password == goodPassword) TgAuth.Ready("Mia Tesfaye", "+251911234567")
        else TgAuth.WaitPassword("your first pet", "Wrong password. Try again.")
    }

    override suspend fun resendCode() {
        calls += "resend"
        (state.value as? TgAuth.WaitCode)?.let { state.value = it.copy(where = "SMS", resendInSec = null) }
    }

    override suspend fun logOut() {
        calls += "logout"
        state.value = TgAuth.Off
    }

    override suspend fun chats(): List<TgChat> = chats

    override suspend fun audioPage(chatId: Long, fromMessageId: Long, limit: Int): List<TgSong> {
        calls += "page:$chatId:$fromMessageId"
        return songs(chatId).filter { fromMessageId == 0L || it.messageId < fromMessageId }.take(limit)
    }

    override fun newAudio(): Flow<TgSong> = incoming.asSharedFlow()

    override suspend fun chatPhoto(chatId: Long): String? = null

    override suspend fun openAudio(chatId: Long, messageId: Long): TgOpenFile =
        throw UnsupportedOperationException("TgFake can't play audio")

    companion object {
        val chats = listOf(
            TgChat(-1001000000001, "Afrobeats Central", TgChatKind.Channel, 48_200),
            TgChat(-1001000000002, "Ethio Jazz Vault", TgChatKind.Channel, 12_900),
            TgChat(-1001000000003, "Lagos Nights", TgChatKind.Channel, 233_000),
            TgChat(-1001000000004, "Gym Mix Friends", TgChatKind.Group, 14),
            TgChat(-1001000000005, "Amapiano Only", TgChatKind.Channel, 91_500),
            TgChat(-1001000000006, "Family chat", TgChatKind.Group, 9),
            TgChat(-1001000000007, "Old school Hip-Hop", TgChatKind.Channel, 5_400),
            TgChat(-1001000000008, "Campus radio club", TgChatKind.Group, 312),
        )

        private val titles = listOf(
            "Essence", "Calm Down", "Last Last", "Peru", "Love Nwantiti", "Water", "Ye", "Rush", "Soweto",
            "Unavailable", "Joha", "Tizita", "Yene Konjo", "Bloody Samaritan", "Fall", "City Boys",
        )
        private val artists = listOf("Wizkid", "Rema", "Burna Boy", "Fireboy DML", "CKay", "Tyla", "Teddy Afro", "Mulatu Astatke")

        /** How many songs a chat has: a few dozen to a couple of hundred. */
        fun totalSongs(chatId: Long): Int = 30 + Math.floorMod(chatId, 10L).toInt() * 22

        /** All songs of a chat, newest first (message ids count down from 5000). */
        fun songsOf(chatId: Long, total: Int = totalSongs(chatId)): List<TgSong> = (0 until total).map { i ->
            val k = (Math.floorMod(chatId, 100L).toInt() + i * 7) % 1000
            TgSong(
                chatId = chatId, messageId = 5000L - i * 3, title = "${titles[k % titles.size]}${if (i >= titles.size) " (${i / titles.size + 1})" else ""}",
                artist = if (i % 11 == 10) "" else artists[k % artists.size], durationSec = 150 + (k % 120),
                mime = "audio/mpeg", sizeBytes = 4_000_000L + k * 1000, fileName = "song$i.mp3", dateSec = 1_700_000_000L - i * 86_400L,
            )
        }
    }
}
