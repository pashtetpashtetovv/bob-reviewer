package com.pashtetovv.bob_reviewer.controller

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubChatClientBuilderConfig::class)
@TestPropertySource(properties = ["ai.access-token=test-token"])
class ChatControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `rejects request without token`() {
        mockMvc.perform(get("/ai").param("message", "hi"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `rejects oversized message`() {
        mockMvc.perform(
            get("/ai")
                .header("X-AI-Token", "test-token")
                .param("message", "a".repeat(4_001)),
        ).andExpect(status().`is`(413))
    }
}

@TestConfiguration
internal class StubChatClientBuilderConfig {
    @Bean
    fun chatClientBuilder(): ChatClient.Builder =
        mock(ChatClient.Builder::class.java).also { builder ->
            `when`(builder.build()).thenReturn(mock(ChatClient::class.java))
        }
}
