package com.pashtetovv.bob_reviewer.controller

import com.pashtetovv.bob_reviewer.service.WebhookOutcome
import com.pashtetovv.bob_reviewer.service.WebhookService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
internal class WebhookController(private val webhookService: WebhookService) {
    @PostMapping("/webhook", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun handleWebhook(
        @RequestHeader("X-GitHub-Event") event: String,
        @RequestHeader("X-GitHub-Delivery") deliveryId: String,
        @RequestHeader("X-Hub-Signature-256", required = false) signature: String?,
        @RequestBody body: String,
    ): ResponseEntity<Void> =
        webhookService.handle(event, deliveryId, signature, body).toResponse()
}

private fun WebhookOutcome.toResponse(): ResponseEntity<Void> = when (this) {
    WebhookOutcome.INVALID_SIGNATURE -> ResponseEntity.status(401).build()
    WebhookOutcome.MALFORMED_PAYLOAD -> ResponseEntity.badRequest().build()
    WebhookOutcome.DUPLICATE, WebhookOutcome.PING -> ResponseEntity.ok().build()
    WebhookOutcome.IGNORED_EVENT, WebhookOutcome.IGNORED_ACTION -> ResponseEntity.noContent().build()
    WebhookOutcome.QUEUED -> ResponseEntity.accepted().build()
}
