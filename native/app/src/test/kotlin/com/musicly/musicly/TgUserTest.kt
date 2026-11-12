package com.musicly.musicly

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musicly.musicly.data.Store
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.net.TelegramBackup
import com.musicly.musicly.tg.TgFake
import com.musicly.musicly.tg.TgUserState
import com.musicly.musicly.tg.isTgUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TgUserTest {
    private lateinit var store: Store

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("musicly", Context.MODE_PRIVATE).edit().clear().putBoolean("_migrated", true).commit()
        store = Store(ctx)
    }

    private val chat = TgFake.chats[0]

    @Test
    fun firstReadStopsAt300ThenOlderContinues() = runTest {
        val fake = TgFake(songsPerChat = 400)
        val state = TgUserState(store, this, {}, {})
        state.attach { fake }
        state.setPicked(chat, true)
        advanceUntilIdle()
        val c = state.chat(chat.id)!!
        assertEquals(300, c.tracks.size)
        assertFalse(state.isDone(chat.id))
        // Six pages of 50, never faster than one call every 1.2 s.
        assertEquals(6, fake.calls.count { it.startsWith("page:") })
        assertTrue("took ${currentTime}ms", currentTime >= 5 * 1200)
        // Newest first, in the format the player expects.
        assertEquals("tu:${chat.id}:5000", c.tracks.first().id)
        assertEquals("td://${chat.id}/5000", c.tracks.first().uri)
        assertTrue(c.tracks.all { it.isTgUser })
        assertEquals(c.tracks.sortedByDescending { TgUserState.messageIdOf(it) }, c.tracks)

        state.loadOlder(chat.id)
        advanceUntilIdle()
        assertEquals(400, c.tracks.size)
        assertTrue(state.isDone(chat.id))
        assertEquals(c.tracks.map { it.id }.toSet().size, c.tracks.size)
    }

    @Test
    fun refreshOnlyFetchesNewerSongs() = runTest {
        val fake = TgFake(songsPerChat = 120)
        val state = TgUserState(store, this, {}, {})
        state.attach { fake }
        state.setPicked(chat, true)
        advanceUntilIdle()
        val c = state.chat(chat.id)!!
        assertEquals(120, c.tracks.size)

        fake.calls.clear()
        fake.addNewer(7)
        state.refreshAll()
        advanceUntilIdle()
        assertEquals(127, c.tracks.size)
        assertEquals("New song 7", c.tracks.first().title)
        // One page was enough: it already reached songs we had.
        assertEquals(1, fake.calls.count { it.startsWith("page:") })
    }

    @Test
    fun savesWithoutAnyKeyAndLoadsBack() = runTest {
        val fake = TgFake(songsPerChat = 60)
        val state = TgUserState(store, this, {}, {})
        state.attach { fake }
        state.saveKey(123456, "0123456789abcdef0123456789abcdef")
        state.setPicked(chat, true)
        advanceUntilIdle()
        state.persist()
        val saved = store.string("tguser")!!
        assertFalse(saved.contains("tg_api"))
        assertFalse(saved.contains("0123456789abcdef"))
        assertTrue(saved.contains("td://"))

        val again = TgUserState(store, this, {}, {})
        again.load()
        assertEquals(60, again.chat(chat.id)!!.tracks.size)
        assertTrue(again.isDone(chat.id))
        assertEquals(123456 to "0123456789abcdef0123456789abcdef", again.savedKey())
    }

    @Test
    fun logOutForgetsChatsButKeepsTheKey() = runTest {
        val fake = TgFake(songsPerChat = 60)
        val state = TgUserState(store, this, {}, {})
        state.attach { fake }
        state.saveKey(5, "abcdefabcdefabcdefabcdef")
        state.setPicked(chat, true)
        advanceUntilIdle()
        state.logOut()
        assertTrue(state.chats.isEmpty())
        assertNull(store.string("tguser"))
        assertTrue("logout" in fake.calls)
        assertEquals(5, state.savedKey()?.first)
    }

    // ---- backups never carry the key or the session ----------------------------------------

    @Test
    fun backupNeverContainsKeyOrSession() {
        store.put("tg_api_id", 123456L)
        store.put("tg_api_hash", "SECRETHASH0123456789abcdef012345")
        store.put("tg_ready", true)
        store.put("tg_name", "Mia")
        store.put("tg_session_blob", "xyz")
        store.put("theme", "dark")
        store.put("telegram", """{"token":"1:SECRETTOKEN","bot":"b","channels":[]}""")
        store.put("tguser", """{"chats":[{"id":-1,"name":"C","tracks":[]}],"done":[]}""")

        val snap = TelegramBackup.snapshot(store)
        val text = snap.toString()
        for (bad in listOf("tg_api_id", "tg_api_hash", "tg_ready", "tg_name", "tg_session", "SECRETHASH", "123456", "SECRETTOKEN")) {
            assertFalse("backup contains $bad", text.contains(bad))
        }
        val prefs = (snap["prefs"] as JsonObject)
        assertEquals("dark", (prefs["theme"] as JsonPrimitive).content)
        assertTrue(prefs.containsKey("tguser"))
    }

    @Test
    fun restoreKeepsTheCurrentKeyAndIgnoresKeysInOldFiles() {
        store.put("tg_api_id", 111L)
        store.put("tg_api_hash", "mine")
        val backup = parseJson(
            """{"app":"musicly","version":1,"prefs":{"theme":"dark","tg_api_id":999,"tg_api_hash":"theirs","tguser":"{\"chats\":[]}"}}""",
        ) as JsonObject
        TelegramBackup.apply(store, backup)
        assertEquals(111L, store.long("tg_api_id"))
        assertEquals("mine", store.string("tg_api_hash"))
        assertEquals("dark", store.string("theme"))
        assertEquals("""{"chats":[]}""", store.string("tguser"))
    }
}
