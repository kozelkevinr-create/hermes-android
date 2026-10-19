package com.hermes.client.data.matrix

import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateSummaryTest {
    private val pem = """
-----BEGIN CERTIFICATE-----
MIIDPTCCAiWgAwIBAgIUIegD4lrrBW46qT4xqX1dmZ82epEwDQYJKoZIhvcNAQEL
BQAwLTEVMBMGA1UEAwwMVGVzdCBIb21lIENBMRQwEgYDVQQKDAtIZXJtZXMgVGVz
dDAgFw0yNjEwMTkxODA2NDlaGA8yMTI2MDkyNTE4MDY0OVowLTEVMBMGA1UEAwwM
VGVzdCBIb21lIENBMRQwEgYDVQQKDAtIZXJtZXMgVGVzdDCCASIwDQYJKoZIhvcN
AQEBBQADggEPADCCAQoCggEBAMOkGmvoOdlUrlv3NqGTN1daYeSIq3jdKnXozjrs
+k50C52PVu6AfK3Cgno8pc20qORS5aV/Cg3wX+iM5JkVU0JZPHZF8pLis9u8Ju48
wXxeqMGTVYQyXGwB0fyOMUaOoan7QFqp2lCUV5VInyNW9ISt2rDr7bPEhszM0O4h
GeKbTH8XyZCTGXsUc6TQXbn1k5DT1dKWtNdALt7An1pcqO10CkwPGOmpXIVqo/lM
1Lvo0CdYXAhv6NBg71nUeU/3I49dZByQIf3JL1+KXn0qNidZTY162xnTH3/bFZq0
djBF3rNE+3SN2AvisQsIEtU9MYAOk8opa7lOIvFHUmOs6kUCAwEAAaNTMFEwHQYD
VR0OBBYEFDdqsO0XsnpYWpQZ/iwaZzMp5i5AMB8GA1UdIwQYMBaAFDdqsO0XsnpY
WpQZ/iwaZzMp5i5AMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEB
AEWxQJcRz5tSKz90OytCw0gPbHuuoZ9rWb1YY1mgsBsxtl3zv9qiGVgUlGqvDpmS
T7Yip26cc59kDMbHcg0t7cEAI6lBo/5QyQe7jAXGli7wSG004wgB6rj0MK/dYtW0
uQ1Spu1N9pZmMZaI8+dZaiCRqtcsp8JTpBRaJU9xrkKeJ+hiUV+wRrdxDl9ntRDp
s0ThXqGg6hE3jrC+8E0aarEoeJnLtuCVKlI+pMTkUOHM/6ewIMDxPa3kVOOhguNV
3YPd/MW1dFaAXuVzpXcQ9PNcKOgufhxIJ4Zmj9BqtihCyIrdPDRYGgBtEYYFgjAo
jHobOBj9Ro6WJqgm3fCYaHc=
-----END CERTIFICATE-----
""".trimIndent()

    @Test fun describes_subject_and_expiry() {
        val der = CertificateBytes.parse(pem.toByteArray()).single()
        val s = CertificateSummary.describe(der)
        assertTrue(s, s.contains("CN=Test Home CA"))
        assertTrue(s, s.contains("expires 2126-09-25"))
    }
}
