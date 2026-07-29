package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayModeStoreTest {
    @Test
    fun `defaults to FAKE when nothing has been set`() {
        val store = InMemoryGatewayModeStore()
        assertEquals(GatewayMode.FAKE, store.get())
    }

    @Test
    fun `set then get round-trips`() {
        val store = InMemoryGatewayModeStore()
        store.set(GatewayMode.REAL)
        assertEquals(GatewayMode.REAL, store.get())
    }
}
