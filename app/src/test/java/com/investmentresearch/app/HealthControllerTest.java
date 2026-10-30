package com.investmentresearch.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.investmentresearch.app.api.HealthController;
import com.investmentresearch.app.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HealthController.class, properties = "app.api-token=test-token")
@Import(SecurityConfig.class)
class HealthControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void health_returns_ok_without_token() throws Exception {
        mvc.perform(get("/health"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
           .andExpect(content().string("ok"));
    }
}
