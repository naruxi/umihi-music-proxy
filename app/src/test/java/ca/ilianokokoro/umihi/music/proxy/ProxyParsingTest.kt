package ca.ilianokokoro.umihi.music.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the subscription parsing and config generation that the proxy feature depends on.
 *
 * These cover the four shapes providers actually serve (Clash YAML, share links, and both
 * wrapped in base64) plus the sing-box config shape, because a silent parser regression here
 * shows up in production as "subscription imported, nothing plays".
 */
class ProxyParsingTest {

    private val clashYaml = """
        port: 7890
        proxies:
          - name: "SS-节点"
            type: ss
            server: 1.2.3.4
            port: 8388
            cipher: aes-256-gcm
            password: "pw123"
          - name: "VMess-WS"
            type: vmess
            server: a.example.com
            port: 443
            uuid: b831381d-6324-4d53-ad4f-8cda48b30811
            alterId: 0
            cipher: auto
            tls: true
            servername: a.example.com
            network: ws
            ws-opts:
              path: /ray
              headers:
                Host: a.example.com
          - name: "VLESS-REALITY"
            type: vless
            server: b.example.com
            port: 443
            uuid: b831381d-6324-4d53-ad4f-8cda48b30811
            flow: xtls-rprx-vision
            tls: true
            servername: www.microsoft.com
            network: tcp
            reality-opts:
              public-key: jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0
              short-id: 0123abcd
          - name: "Trojan-gRPC"
            type: trojan
            server: d.example.com
            port: 443
            password: tpass
            sni: d.example.com
            network: grpc
            grpc-opts:
              grpc-service-name: gun
          - name: "Hy2"
            type: hysteria2
            server: e.example.com
            port: 443
            password: hypass
            sni: e.example.com
            skip-cert-verify: true
        proxy-groups:
          - name: PROXY
            type: select
            proxies: ["SS-节点", "VMess-WS"]
        rules:
          - MATCH,PROXY
    """.trimIndent() + "\n"

    private val shareLinks = listOf(
        "vmess://eyJ2IjoiMiIsInBzIjoidm1lc3Mtbm9kZSIsImFkZCI6ImMuZXhhbXBsZS5jb20iLCJwb3J0IjoiNDQzIiwiaWQiOiJiODMxMzgxZC02MzI0LTRkNTMtYWQ0Zi04Y2RhNDhiMzA4MTEiLCJhaWQiOiIwIiwic2N5IjoiYXV0byIsIm5ldCI6IndzIiwidHlwZSI6Im5vbmUiLCJob3N0IjoiYy5leGFtcGxlLmNvbSIsInBhdGgiOiIvd3MiLCJ0bHMiOiJ0bHMiLCJzbmkiOiJjLmV4YW1wbGUuY29tIn0=",
        "ss://YWVzLTI1Ni1nY206cHcxMjM=@d.example.com:8388#SS-Node",
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@e.example.com:443?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=0123&type=tcp&flow=xtls-rprx-vision#VLESS-Node",
        "trojan://tpass@f.example.com:443?sni=f.example.com&type=grpc&serviceName=gun#Trojan-Node",
        "hysteria2://hypass@g.example.com:443?sni=g.example.com&insecure=1&obfs=salamander&obfs-password=ob#HY2-Node",
    ).joinToString("\n")

    @Test
    fun `parses clash yaml and maps fields`() {
        val nodes = SubscriptionParser.parse(clashYaml)

        assertEquals(5, nodes.size)
        assertEquals("ss", nodes[0].type)
        assertEquals("aes-256-gcm", nodes[0].method)

        assertEquals("/ray", nodes[1].wsPath)
        assertEquals("a.example.com", nodes[1].wsHost)
        assertTrue(nodes[1].tls)

        assertNotNull(nodes[2].realityPublicKey)
        assertEquals("xtls-rprx-vision", nodes[2].flow)

        assertEquals("gun", nodes[3].grpcServiceName)
        assertTrue(nodes[4].insecure)
    }

    @Test
    fun `parses share links`() {
        val nodes = SubscriptionParser.parse(shareLinks)

        assertEquals(5, nodes.size)
        assertEquals("vmess", nodes[0].type)
        assertEquals("/ws", nodes[0].wsPath)
        assertTrue(nodes[0].tls)

        assertEquals("pw123", nodes[1].password)
        assertEquals("SS-Node", nodes[1].name)

        assertEquals("0123", nodes[2].realityShortId)
        assertEquals("chrome", nodes[2].fingerprint)

        assertEquals("grpc", nodes[3].network)
        assertEquals("salamander", nodes[4].obfs)
    }

    @Test
    fun `parses base64 wrapped payloads`() {
        val wrappedLinks = java.util.Base64.getEncoder()
            .encodeToString(shareLinks.toByteArray())
        assertEquals(5, SubscriptionParser.parse(wrappedLinks).size)

        val wrappedYaml = java.util.Base64.getEncoder()
            .encodeToString(clashYaml.toByteArray())
        assertEquals(5, SubscriptionParser.parse(wrappedYaml).size)
    }

    @Test
    fun `rejects garbage without throwing`() {
        assertTrue(SubscriptionParser.parse("not a subscription").isEmpty())
        assertTrue(SubscriptionParser.parse("").isEmpty())
    }

    @Test
    fun `builds a valid sing-box config for every node`() {
        val nodes = SubscriptionParser.parse(clashYaml) + SubscriptionParser.parse(shareLinks)

        nodes.forEach { node ->
            val config = SingBoxConfig.build(node, 17890)
            assertTrue("missing inbound", config.contains("\"listen_port\":17890"))
            assertTrue("missing proxy outbound", config.contains("\"tag\":\"proxy\""))
            assertTrue("missing server", config.contains(node.server))
            assertTrue("missing type", config.contains(node.type))
        }
    }
}
