package com.pashtetovv.bob_reviewer.service

import com.pashtetovv.bob_reviewer.dto.GitHubPullRequestEvent
import com.pashtetovv.bob_reviewer.repository.WebhookEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class WebhookOutcome {
    INVALID_SIGNATURE,
    DUPLICATE,
    PING,
    IGNORED_EVENT,
    MALFORMED_PAYLOAD,
    IGNORED_ACTION,
    QUEUED,
}

@Service
internal class WebhookService(
    private val reviewService: PullRequestReviewService,
    private val webhookEventRepository: WebhookEventRepository,
    private val objectMapper: ObjectMapper,
    @Qualifier("applicationTaskExecutor") private val taskExecutor: TaskExecutor,
    @Value("\${github.webhook.secret:}") private val webhookSecret: String,
) {
    private val signatureVerificationEnabled = webhookSecret.isNotBlank()

    init {
        if (!signatureVerificationEnabled) {
            log.warn("Webhook signature verification disabled: github.webhook.secret is blank")
        }
    }

    fun handle(event: String, deliveryId: String, signature: String?, body: String): WebhookOutcome {
        if (!isValidSignature(body, signature)) {
            log.warn("Rejected webhook {}: invalid signature", deliveryId)
            return WebhookOutcome.INVALID_SIGNATURE
        }

        if (event == "ping") {
            webhookEventRepository.saveIfNew(deliveryId, event, body)
            return WebhookOutcome.PING
        }
        if (event != "pull_request") {
            webhookEventRepository.saveIfNew(deliveryId, event, body)
            return WebhookOutcome.IGNORED_EVENT
        }

        val payload = try {
            objectMapper.readValue(body, GitHubPullRequestEvent::class.java)
        } catch (e: Exception) {
            log.warn("Rejected webhook {}: malformed payload: {}", deliveryId, e.message)
            return WebhookOutcome.MALFORMED_PAYLOAD
        }

        // Save only after parsing so a 400 can be retried with the same delivery ID.
        if (!webhookEventRepository.saveIfNew(deliveryId, event, body)) {
            log.info("Ignoring duplicate delivery {}", deliveryId)
            return WebhookOutcome.DUPLICATE
        }

        if (payload.action !in REVIEW_ACTIONS || payload.pullRequest.draft == true) {
            log.info(
                "Ignoring {} action for {}#{}",
                payload.action,
                payload.repository.fullName,
                payload.number,
            )
            return WebhookOutcome.IGNORED_ACTION
        }

        taskExecutor.execute {
            runCatching {
                reviewService.review(payload)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { webhookEventRepository.updateComment(deliveryId, it) }
            }.onFailure { log.error("Review failed for delivery {}", deliveryId, it) }
        }
        log.info(
            "Queued review for {}#{} ({}, delivery {})",
            payload.repository.fullName,
            payload.number,
            payload.action,
            deliveryId,
        )
        return WebhookOutcome.QUEUED
    }

    private fun isValidSignature(body: String, signature: String?): Boolean {
        if (!signatureVerificationEnabled) {
            return true
        }
        if (signature == null || !signature.startsWith(SHA256_PREFIX)) {
            return false
        }
        val actual = runCatching {
            HexFormat.of().parseHex(signature.removePrefix(SHA256_PREFIX))
        }.getOrNull() ?: return false
        return MessageDigest.isEqual(hmac(body), actual)
    }

    private fun hmac(body: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(webhookSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WebhookService::class.java)
        private const val SHA256_PREFIX = "sha256="
        private val REVIEW_ACTIONS = setOf("opened", "reopened", "synchronize")
    }
}
