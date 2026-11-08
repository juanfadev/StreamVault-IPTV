package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.domain.util.XtreamSlotDecision
import com.streamvault.domain.util.XtreamSlotPolicy
import kotlinx.serialization.json.Json
import org.junit.Test

class XtreamAccountProbeMappingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val now = 1_800_000_000L

    private fun decide(userInfo: String): XtreamSlotDecision {
        val response = json.decodeFromString<XtreamAuthResponse>(
            """{"user_info":$userInfo,"server_info":{"url":"host.test","port":"80"}}"""
        )
        return XtreamSlotPolicy.decide(response.userInfo.toAccountStatus(), now)
    }

    @Test
    fun `active account with a free connection is kept`() {
        assertThat(
            decide("""{"auth":1,"status":"Active","exp_date":"1900000000","active_cons":"0","max_connections":"1"}""")
        ).isEqualTo(XtreamSlotDecision.FREE_SLOT)
    }

    @Test
    fun `active account with every connection in use has no slots`() {
        assertThat(
            decide("""{"auth":1,"status":"Active","exp_date":"1900000000","active_cons":"2","max_connections":"2"}""")
        ).isEqualTo(XtreamSlotDecision.NO_SLOTS)
    }

    @Test
    fun `unlimited connections and missing expiry are kept`() {
        assertThat(
            decide("""{"auth":1,"status":"Active","exp_date":null,"active_cons":"5","max_connections":"0"}""")
        ).isEqualTo(XtreamSlotDecision.FREE_SLOT)
    }

    @Test
    fun `expired, banned and rejected accounts are discarded`() {
        assertThat(decide("""{"auth":1,"status":"Expired","max_connections":"1","active_cons":"0"}"""))
            .isEqualTo(XtreamSlotDecision.INACTIVE)
        assertThat(decide("""{"auth":1,"status":"Banned","max_connections":"1","active_cons":"0"}"""))
            .isEqualTo(XtreamSlotDecision.INACTIVE)
        assertThat(decide("""{"auth":1,"status":"Active","exp_date":"1700000000","max_connections":"1","active_cons":"0"}"""))
            .isEqualTo(XtreamSlotDecision.INACTIVE)
        assertThat(decide("""{"auth":0,"status":"","message":"Invalid"}"""))
            .isEqualTo(XtreamSlotDecision.INVALID)
    }
}
