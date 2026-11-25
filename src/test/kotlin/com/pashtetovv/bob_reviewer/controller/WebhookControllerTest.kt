package com.pashtetovv.bob_reviewer.controller

import com.pashtetovv.bob_reviewer.dto.GitHubPullRequestEvent
import com.pashtetovv.bob_reviewer.service.PullRequestReviewService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SyncTaskExecutorConfig::class)
@TestPropertySource(properties = ["github.webhook.secret=test-secret"])
class WebhookControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var reviewService: PullRequestReviewService

    @Test
    fun `queues review for opened pull request`() {
        perform(prEvent("opened"))
            .andExpect(status().isAccepted)

        val invocations = mockingDetails(reviewService).invocations
        assertEquals(1, invocations.size)
        val event = invocations.single().arguments.single() as GitHubPullRequestEvent
        assertEquals("owner/repo", event.repository.fullName)
        assertEquals(42, event.number)
    }

    @Test
    fun `rejects invalid signature`() {
        perform(prEvent("opened"), signature = "sha256=deadbeef")
            .andExpect(status().isUnauthorized)

        verifyNoInteractions(reviewService)
    }

    @Test
    fun `rejects malformed payload`() {
        val body = """{"action":"opened"}"""
        perform(body)
            .andExpect(status().isBadRequest)

        verifyNoInteractions(reviewService)
    }

    @Test
    fun `allows redelivery after malformed payload`() {
        val deliveryId = UUID.randomUUID().toString()
        perform("""{"action":"opened"}""", deliveryId = deliveryId)
            .andExpect(status().isBadRequest)
        perform(prEvent("opened"), deliveryId = deliveryId)
            .andExpect(status().isAccepted)

        assertEquals(1, mockingDetails(reviewService).invocations.size)
    }

    @Test
    fun `ignores ping`() {
        perform("""{"zen":"Keep it logically awesome."}""", event = "ping")
            .andExpect(status().isOk)

        verifyNoInteractions(reviewService)
    }

    @Test
    fun `ignores non pull request events`() {
        perform(prEvent("opened"), event = "push")
            .andExpect(status().isNoContent)

        verifyNoInteractions(reviewService)
    }

    @Test
    fun `ignores closed pull requests`() {
        perform(prEvent("closed"))
            .andExpect(status().isNoContent)

        verifyNoInteractions(reviewService)
    }

    @Test
    fun `ignores draft pull requests`() {
        perform(prEvent("opened", draft = true))
            .andExpect(status().isNoContent)

        verifyNoInteractions(reviewService)
    }

    @Test
    fun `ignores duplicate delivery`() {
        val deliveryId = UUID.randomUUID().toString()
        perform(prEvent("opened"), deliveryId = deliveryId)
            .andExpect(status().isAccepted)
        perform(prEvent("opened"), deliveryId = deliveryId)
            .andExpect(status().isOk)

        assertEquals(1, mockingDetails(reviewService).invocations.size)
    }

    private fun perform(
        body: String,
        event: String = "pull_request",
        signature: String = sign(body),
        deliveryId: String = UUID.randomUUID().toString(),
    ) = mockMvc.perform(
        post("/webhook")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-GitHub-Event", event)
            .header("X-GitHub-Delivery", deliveryId)
            .header("X-Hub-Signature-256", signature)
            .content(body),
    )

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("test-secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)))
    }
}

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SyncTaskExecutorConfig::class)
@TestPropertySource(properties = ["github.webhook.secret="])
class WebhookControllerWithoutSecretTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var reviewService: PullRequestReviewService

    @Test
    fun `accepts unsigned webhook when secret is blank`() {
        mockMvc.perform(
            post("/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .content(prEvent("opened")),
        ).andExpect(status().isAccepted)

        assertEquals(1, mockingDetails(reviewService).invocations.size)
    }
}

internal fun prEvent(action: String, draft: Boolean = false) = """
    {
      "action": "$action",
      "number": 42,
      "pull_request": {
        "title": "Fix bug",
        "body": "Description",
        "draft": $draft,
        "html_url": "https://github.com/owner/repo/pull/42",
        "user": {"login": "pavel"},
        "head": {"sha": "abc123", "ref": "feature"},
        "base": {"sha": "def456", "ref": "main"}
      },
      "repository": {
        "full_name": "owner/repo",
        "html_url": "https://github.com/owner/repo"
      }
    }
""".trimIndent()

@TestConfiguration
internal class SyncTaskExecutorConfig {
    @Bean
    fun applicationTaskExecutor(): TaskExecutor = SyncTaskExecutor()
}
