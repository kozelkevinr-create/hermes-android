package com.hermes.client.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixDefaultsTest {
    @Test fun parses_the_three_keys() {
        val d = MatrixDefaults.parse("homeserver=https://h:8443\nusername=alice\nbotMxid=@bot:example.test\n")
        assertEquals(MatrixDefaults("https://h:8443", "alice", "@bot:example.test"), d)
    }

    @Test fun empty_and_missing_keys_become_blank() {
        assertEquals(MatrixDefaults(), MatrixDefaults.parse("homeserver=\nusername=\nbotMxid=\n"))
        assertEquals(MatrixDefaults(), MatrixDefaults.parse(""))
    }

    @Test fun trims_whitespace_and_quotes() {
        assertEquals("alice", MatrixDefaults.of(null, "  \"alice\" ", null).username)
    }

    @Test fun ignores_unknown_keys_such_as_a_password() {
        val d = MatrixDefaults.parse("username=alice\npassword=hunter2\n")
        assertEquals(MatrixDefaults(username = "alice"), d)
    }
}
