package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamBulkImportParserTest {

    @Test
    fun `parses get php urls with port, extra slashes and missing output`() {
        val lines = XtreamBulkImportParser.parse(
            """
            http://stream8k.store:80/get.php?username=alice&password=secret1&type=m3u_plus&output=ts

            #EXTM3U
            http://vexqari.com//get.php?username=bob&password=p%40ss+word&type=m3u_plus
            https://iptvgo.cc:8443/player_api.php?username=carol&password=xyz
            iptvgo.cc:8080/get.php?password=pw&username=dave
            not a url
            http://host.test/get.php?username=erin
            http://user:pw@host.test/get.php?username=frank&password=pw
            """.trimIndent()
        )

        val accounts = lines.filterIsInstance<XtreamBulkImportLine.Account>()
        assertThat(accounts.map { it.lineNumber }).containsExactly(1, 4, 5, 6).inOrder()
        assertThat(lines.filterIsInstance<XtreamBulkImportLine.Invalid>().map { it.lineNumber })
            .containsExactly(7, 8, 9).inOrder()

        val alice = accounts[0].account
        assertThat(alice.serverUrl).isEqualTo("http://stream8k.store:80")
        assertThat(alice.hostLabel).isEqualTo("stream8k.store")
        assertThat(alice.username).isEqualTo("alice")
        assertThat(alice.password).isEqualTo("secret1")

        val bob = accounts[1].account
        assertThat(bob.serverUrl).isEqualTo("http://vexqari.com")
        assertThat(bob.password).isEqualTo("p@ss+word")

        val carol = accounts[2].account
        assertThat(carol.serverUrl).isEqualTo("https://iptvgo.cc:8443")
        assertThat(carol.hostLabel).isEqualTo("iptvgo.cc:8443")

        val dave = accounts[3].account
        assertThat(dave.serverUrl).isEqualTo("http://iptvgo.cc:8080")
        assertThat(dave.username).isEqualTo("dave")
    }

    @Test
    fun `identity ignores default port and host case`() {
        val withPort = XtreamBulkImportParser.parseLine("http://STREAM8K.store:80/get.php?username=u1&password=a")!!
        val withoutPort = XtreamBulkImportParser.parseLine("http://stream8k.store/get.php?username=u1&password=b")!!
        assertThat(withPort.identityKey).isEqualTo(withoutPort.identityKey)
        assertThat(XtreamBulkImportParser.identityKeyForServer("http://stream8k.store:80", "u1"))
            .isEqualTo(withPort.identityKey)
        assertThat(XtreamBulkImportParser.identityKeyForServer("http://stream8k.store:8080", "u1"))
            .isNotEqualTo(withPort.identityKey)
    }

    @Test
    fun `account string representation never contains the password`() {
        val account = XtreamBulkImportParser.parseLine("http://host.test/get.php?username=u&password=topsecret")!!
        assertThat(account.toString()).doesNotContain("topsecret")
    }

    private fun status(
        status: String = "Active",
        max: Int? = 1,
        active: Int? = 0,
        expires: Long? = null,
        authenticated: Boolean = true
    ) = XtreamAccountStatus(authenticated, status, max, active, expires)

    @Test
    fun `slot policy keeps only active accounts with a free connection`() {
        val now = 1_800_000_000L
        assertThat(XtreamSlotPolicy.decide(status(max = 2, active = 1), now)).isEqualTo(XtreamSlotDecision.FREE_SLOT)
        assertThat(XtreamSlotPolicy.decide(status(max = 1, active = 1), now)).isEqualTo(XtreamSlotDecision.NO_SLOTS)
        assertThat(XtreamSlotPolicy.decide(status(max = 1, active = 3), now)).isEqualTo(XtreamSlotDecision.NO_SLOTS)
        assertThat(XtreamSlotPolicy.decide(status(max = 0, active = 9), now)).isEqualTo(XtreamSlotDecision.FREE_SLOT)
        assertThat(XtreamSlotPolicy.decide(status(max = null, active = null), now)).isEqualTo(XtreamSlotDecision.FREE_SLOT)
        assertThat(XtreamSlotPolicy.decide(status(status = "Expired"), now)).isEqualTo(XtreamSlotDecision.INACTIVE)
        assertThat(XtreamSlotPolicy.decide(status(status = "Banned"), now)).isEqualTo(XtreamSlotDecision.INACTIVE)
        assertThat(XtreamSlotPolicy.decide(status(status = "Disabled"), now)).isEqualTo(XtreamSlotDecision.INACTIVE)
        assertThat(XtreamSlotPolicy.decide(status(expires = now - 1), now)).isEqualTo(XtreamSlotDecision.INACTIVE)
        assertThat(XtreamSlotPolicy.decide(status(expires = now + 3600), now)).isEqualTo(XtreamSlotDecision.FREE_SLOT)
        assertThat(XtreamSlotPolicy.decide(status(authenticated = false), now)).isEqualTo(XtreamSlotDecision.INVALID)
    }
}
