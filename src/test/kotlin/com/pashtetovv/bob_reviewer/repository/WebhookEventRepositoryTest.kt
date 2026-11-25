package com.pashtetovv.bob_reviewer.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class WebhookEventRepositoryTest {
    @Autowired
    private lateinit var repository: WebhookEventRepository

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Test
    fun `saves event once and rejects duplicate delivery`() {
        val deliveryId = "test-${UUID.randomUUID()}"
        val payload = """{"action":"opened"}"""

        assertTrue(repository.saveIfNew(deliveryId, "pull_request", payload))
        assertFalse(repository.saveIfNew(deliveryId, "pull_request", payload))

        val storedPayload = jdbcClient.sql(
            "select payload from github_webhook_event where delivery_id = :deliveryId",
        )
            .param("deliveryId", deliveryId)
            .query(String::class.java)
            .single()
        assertEquals(payload, storedPayload)

        assertEquals(
            1,
            jdbcClient.sql(
                "select count(*) from github_webhook_event where delivery_id = :deliveryId and comment is null",
            )
                .param("deliveryId", deliveryId)
                .query(Int::class.java)
                .single(),
        )

        repository.updateComment(deliveryId, "Looks good")

        assertEquals(
            "Looks good",
            jdbcClient.sql("select comment from github_webhook_event where delivery_id = :deliveryId")
                .param("deliveryId", deliveryId)
                .query(String::class.java)
                .single(),
        )
    }
}
