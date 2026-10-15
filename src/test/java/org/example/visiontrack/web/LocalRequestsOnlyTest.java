package org.example.visiontrack.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalRequestsOnlyTest {

    @RestController
    static class AnyApi {
        @DeleteMapping("/api/things/1")
        void delete() {
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnyApi())
            .setControllerAdvice(new LocalRequestsOnly())
            .build();

    @Test
    void requestsFromThisComputerAreServed() throws Exception {
        mvc.perform(delete("/api/things/1").with(r -> { r.setRemoteAddr("127.0.0.1"); return r; }))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/things/1").with(r -> { r.setRemoteAddr("::1"); return r; }))
                .andExpect(status().isOk());
    }

    @Test
    void requestsFromTheNetworkAreRefused() throws Exception {
        mvc.perform(delete("/api/things/1").with(r -> { r.setRemoteAddr("192.168.1.23"); return r; }))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyLoopbackAddressesCount() {
        assertThat(LocalRequestsOnly.isLoopback("127.0.0.1")).isTrue();
        assertThat(LocalRequestsOnly.isLoopback("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(LocalRequestsOnly.isLoopback("10.0.0.5")).isFalse();
        assertThat(LocalRequestsOnly.isLoopback(null)).isFalse();
    }
}
