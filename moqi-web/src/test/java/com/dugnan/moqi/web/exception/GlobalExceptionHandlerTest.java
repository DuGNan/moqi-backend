package com.dugnan.moqi.web.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GlobalExceptionHandlerTest {

    @Test
    void doesNotWriteJsonWhenSseClientHasDisconnected() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DisconnectedSseController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/disconnected-sse"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @RestController
    private static class DisconnectedSseController {

        @GetMapping(value = "/disconnected-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void disconnected() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("client disconnected");
        }
    }
}
