package com.pashtetovv.bob_reviewer.service

import com.pashtetovv.bob_reviewer.client.GitHubClient
import com.pashtetovv.bob_reviewer.dto.GitHubPullRequestEvent
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

@Service
internal class PullRequestReviewService(
    chatClientBuilder: ChatClient.Builder,
    private val gitHubClient: GitHubClient,
) {
    private val chatClient: ChatClient = chatClientBuilder.build()

    fun review(event: GitHubPullRequestEvent): String? {
        val diff = gitHubClient.fetchDiff(event.repository.fullName, event.number)
        log.info("Reviewing PR {}#{}", event.repository.fullName, event.number)
        return chatClient.prompt()
            .user(buildPrompt(event, diff))
            .call()
            .content()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(PullRequestReviewService::class.java)
    }
}

internal fun buildPrompt(event: GitHubPullRequestEvent, diff: String?): String {
    val pullRequest = event.pullRequest
    val diffSection = if (diff.isNullOrBlank()) {
        "Diff: unavailable"
    } else {
        "Diff:\n```diff\n$diff\n```"
    }
    val base = pullRequest.base
    val head = pullRequest.head
    val metadata = """
        Review this GitHub pull request for bugs, security issues, and missing tests.
        Repository: ${event.repository.fullName}
        PR #${event.number}: ${pullRequest.title ?: "(no title)"}
        Author: ${pullRequest.user?.login ?: "(unknown)"}
        URL: ${pullRequest.htmlUrl ?: "(unknown)"}
        Base: ${base?.ref ?: "?"} (${base?.sha ?: "?"})
        Head: ${head?.ref ?: "?"} (${head?.sha ?: "?"})
        Description:
        ${pullRequest.body ?: "(no description)"}
    """.trimIndent()
    return "$metadata\n\n$diffSection"
}
