package com.campus.marketplace.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void takesTheEntryThatManyProxiesFromTheRight() {
        assertThat(ClientIp.of(request("1.1.1.1"), 1)).isEqualTo("1.1.1.1");
        assertThat(ClientIp.of(request("1.1.1.1, 2.2.2.2"), 1)).isEqualTo("2.2.2.2");
        assertThat(ClientIp.of(request("203.0.113.9, 35.191.0.1"), 2)).isEqualTo("203.0.113.9");
    }

    @Test
    void entriesTheClientPrependsCannotChangeTheResult() {
        assertThat(ClientIp.of(request("6.6.6.6, 5.5.5.5, 203.0.113.9, 35.191.0.1"), 2)).isEqualTo("203.0.113.9");
        assertThat(ClientIp.of(request("1.2.3.4, 203.0.113.9, 35.191.0.1"), 2)).isEqualTo("203.0.113.9");
    }

    @Test
    void aShortHeaderUsesTheLeftmostEntry_neverTheSharedProxyAddress() {
        assertThat(ClientIp.of(request("203.0.113.9"), 2)).isEqualTo("203.0.113.9");
    }

    @Test
    void withoutTheHeader_orWithNoTrustedProxies_theConnectionAddressIsUsed() {
        assertThat(ClientIp.of(request(null), 2)).isEqualTo("10.0.0.9");
        assertThat(ClientIp.of(request("1.1.1.1"), 0)).isEqualTo("10.0.0.9");
        assertThat(ClientIp.of(request("  "), 1)).isEqualTo("10.0.0.9");
    }
}
