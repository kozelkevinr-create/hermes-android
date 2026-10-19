package com.hermes.client.data.matrix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeKeyValueStore : SecretKeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putAll(entries: Map<String, String?>) {
        entries.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }
}

fun sampleSession(token: String = "tok1", refresh: String? = "ref1") = MatrixSessionData(
    accessToken = token,
    refreshToken = refresh,
    userId = "@alice:example.test",
    deviceId = "DEV123",
    homeserverUrl = "https://192.0.2.1:8443",
    oauthData = null,
    slidingSyncVersion = "NATIVE",
)

class MatrixAccountStoreTest {
    private val kv = FakeKeyValueStore()
    private var counter = 0
    private fun store() = MatrixAccountStore(kv) { ByteArray(32) { (++counter).toByte() } }

    @Test fun empty_store_has_no_account() {
        assertNull(store().load())
        assertFalse(store().hasAccount())
    }

    @Test fun account_round_trips_including_ca() {
        val ca = listOf(byteArrayOf(0x30, 1, 2, 3, 4), byteArrayOf(0x30, 9, 8, 7, 6))
        val acct = MatrixAccount(sampleSession(), "@bot:example.test", ca)
        store().save(acct)
        val back = store().load()!!
        assertEquals(acct.session, back.session)
        assertEquals("@bot:example.test", back.botMxid)
        assertEquals(2, back.caCerts.size)
        assertArrayEquals(ca[0], back.caCerts[0])
        assertArrayEquals(ca[1], back.caCerts[1])
    }

    @Test fun optional_fields_round_trip_as_absent() {
        store().save(MatrixAccount(sampleSession(refresh = null), "@b:x"))
        val back = store().load()!!
        assertNull(back.session.refreshToken)
        assertTrue(back.caCerts.isEmpty())
    }

    @Test fun update_session_replaces_tokens_only_when_account_exists() {
        val s = store()
        s.updateSession(sampleSession("ignored"))
        assertNull(s.load())
        s.save(MatrixAccount(sampleSession(), "@b:x", listOf(byteArrayOf(0x30, 1, 2, 3, 4))))
        s.updateSession(sampleSession("tok2", "ref2"))
        val back = s.load()!!
        assertEquals("tok2", back.session.accessToken)
        assertEquals("ref2", back.session.refreshToken)
        assertEquals("@b:x", back.botMxid)
        assertEquals(1, back.caCerts.size)
    }

    @Test fun store_slot_round_trips() {
        val s = store()
        s.save(MatrixAccount(sampleSession(), "@b:x", storeSlot = "s1"))
        assertEquals("s1", s.load()!!.storeSlot)
    }

    @Test fun refresh_for_a_different_device_is_ignored() {
        val s = store()
        s.save(MatrixAccount(sampleSession(), "@b:x"))
        s.updateSession(sampleSession("stray").copy(deviceId = "OTHER"))
        assertEquals("tok1", s.load()!!.session.accessToken)
    }

    @Test fun clear_removes_account() {
        val s = store()
        s.save(MatrixAccount(sampleSession(), "@b:x"))
        s.clear()
        assertNull(s.load())
    }

    @Test fun passphrase_is_generated_once_and_reused() {
        val first = store().storePassphrase()
        assertEquals(64, first.length)
        assertEquals(first, store().storePassphrase()) // new instance, same backing
        assertEquals(first, store().storePassphrase())
        assertEquals(32, counter) // generator ran exactly once
    }

    @Test fun passphrase_survives_clear() {
        val s = store()
        val p = s.storePassphrase()
        s.save(MatrixAccount(sampleSession(), "@b:x"))
        s.clear()
        assertEquals(p, s.storePassphrase())
    }

    @Test fun different_stores_get_different_passphrases() {
        val a = MatrixAccountStore(FakeKeyValueStore()) { ByteArray(32) { 1 } }.storePassphrase()
        val b = MatrixAccountStore(FakeKeyValueStore()) { ByteArray(32) { 2 } }.storePassphrase()
        assertNotEquals(a, b)
    }

    @Test fun no_password_key_is_ever_written() {
        store().save(MatrixAccount(sampleSession(), "@b:x"))
        assertTrue(kv.map.keys.none { it.contains("password", ignoreCase = true) })
    }

    @Test fun session_toString_hides_tokens() {
        assertFalse(sampleSession("secret-token").toString().contains("secret-token"))
    }
}
