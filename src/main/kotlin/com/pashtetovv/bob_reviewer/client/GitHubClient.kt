package com.pashtetovv.bob_reviewer.client

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
internal class GitHubClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${github.token:}") private val token: String,
) {
    private val restClient = restClientBuilder
        .baseUrl("https://api.github.com")
        .defaultHeaders { headers ->
            headers.set(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
            headers.set(HttpHeaders.USER_AGENT, "bob-reviewer")
            headers.set("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) {
                headers.setBearerAuth(token)
            }
        }
        .build()

    fun fetchDiff(repositoryFullName: String, number: Int): String? {
        val repository = repositoryFullName.split('/', limit = 2)
        if (repository.size != 2) {
            log.warn("Invalid repository name: {}", repositoryFullName)
            return null
        }

        return try {
            restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", repository[0], repository[1], number)
                .retrieve()
                .body(String::class.java)
                // shortcut: fixed 30k-char cap; switch to per-file chunks if large PRs matter.
                ?.take(MAX_DIFF_LENGTH)
        } catch (e: Exception) {
            log.warn("Failed to fetch diff for {}#{}: {}", repositoryFullName, number, e.message)
            null
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(GitHubClient::class.java)
        private const val MAX_DIFF_LENGTH = 30_000
    }
}
