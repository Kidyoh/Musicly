package com.musicly.musicly

import com.musicly.musicly.jam.base64
import org.junit.Assert.assertEquals
import org.junit.Test

class Base64Test {
    @Test
    fun matchesJdk() {
        for (n in 0..40) {
            val b = ByteArray(n) { (it * 37 + n).toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), base64(b))
        }
    }
}
