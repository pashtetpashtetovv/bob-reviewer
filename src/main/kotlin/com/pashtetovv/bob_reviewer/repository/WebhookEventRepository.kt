package com.pashtetovv.bob_reviewer.repository

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
internal class WebhookEventRepository(private val jdbcClient: JdbcClient) {
    fun saveIfNew(deliveryId: String, eventType: String, payload: String): Boolean =
        try {
            jdbcClient.sql(
                """
                insert into github_webhook_event (delivery_id, event_type, payload)
                values (:deliveryId, :eventType, :payload)
                """.trimIndent(),
            )
                .param("deliveryId", deliveryId)
                .param("eventType", eventType)
                .param("payload", payload)
                .update() == 1
        } catch (_: DuplicateKeyException) {
            false
        }

    fun updateComment(deliveryId: String, comment: String): Int =
        jdbcClient.sql(
            """
            update github_webhook_event
            set comment = :comment
            where delivery_id = :deliveryId
            """.trimIndent(),
        )
            .param("comment", comment)
            .param("deliveryId", deliveryId)
            .update()
}
