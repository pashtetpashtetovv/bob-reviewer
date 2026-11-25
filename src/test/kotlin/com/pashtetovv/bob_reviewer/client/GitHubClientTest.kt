package com.pashtetovv.bob_reviewer.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class GitHubClientTest {
    private val diffMediaType = MediaType.valueOf("application/vnd.github.v3.diff")
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()

    @Test
    fun `fetches diff with token`() {
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
            .andRespond(withSuccess("diff --git a/file b/file", diffMediaType))

        val client = GitHubClient(builder, "secret")

        assertEquals("diff --git a/file b/file", client.fetchDiff("owner/repo", 42))
        server.verify()
    }

    @Test
    fun `fetches public diff without authorization`() {
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andRespond(withSuccess("diff --git a/file b/file", diffMediaType))

        val client = GitHubClient(builder, "")

        assertEquals("diff --git a/file b/file", client.fetchDiff("owner/repo", 42))
        server.verify()
    }

    @Test
    fun `returns null when GitHub rejects the request`() {
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        val client = GitHubClient(builder, "")

        assertNull(client.fetchDiff("owner/repo", 42))
        server.verify()
    }

    @Test
    fun `truncates oversized diff`() {
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
            .andRespond(withSuccess("x".repeat(30_001), diffMediaType))

        val client = GitHubClient(builder, "")

        assertEquals(30_000, client.fetchDiff("owner/repo", 42)?.length)
        server.verify()
    }
}
