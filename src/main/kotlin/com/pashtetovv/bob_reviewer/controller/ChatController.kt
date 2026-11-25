package com.pashtetovv.bob_reviewer.controller

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
internal class ChatController(
    chatClientBuilder: ChatClient.Builder,
    @Value("\${ai.access-token:}") private val accessToken: String,
) {
    private val chatClient: ChatClient = chatClientBuilder.build()

    init {
        if (accessToken.isBlank()) {
            log.warn("AI endpoint is unauthenticated: ai.access-token is blank")
        }
    }

    @GetMapping("/ai")
    fun ask(
        @RequestParam(defaultValue = "Tell me a joke") message: String,
        @RequestHeader(value = "X-AI-Token", required = false) token: String?,
    ): String? {
        if (accessToken.isNotBlank() && token != accessToken) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
        if (message.length > MAX_MESSAGE_LENGTH) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)
        }
        log.info("AI request received ({} chars)", message.length)
        val response = this.chatClient.prompt()
            .user(message)
            .call()
            .content()
        return response
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ChatController::class.java)
        private const val MAX_MESSAGE_LENGTH = 4_000
    }
}
