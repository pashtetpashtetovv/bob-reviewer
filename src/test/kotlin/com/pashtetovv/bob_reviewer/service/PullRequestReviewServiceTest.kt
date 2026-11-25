package com.pashtetovv.bob_reviewer.service

import com.pashtetovv.bob_reviewer.dto.GitHubPullRequest
import com.pashtetovv.bob_reviewer.dto.GitHubPullRequestEvent
import com.pashtetovv.bob_reviewer.dto.GitHubRef
import com.pashtetovv.bob_reviewer.dto.GitHubRepository
import com.pashtetovv.bob_reviewer.dto.GitHubUser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PullRequestReviewServiceTest {
    private val event = GitHubPullRequestEvent(
        action = "opened",
        number = 42,
        pullRequest = GitHubPullRequest(
            title = "Fix bug",
            htmlUrl = "https://github.com/owner/repo/pull/42",
            user = GitHubUser("pavel"),
            head = GitHubRef("abc123", "feature"),
            base = GitHubRef("def456", "main"),
        ),
        repository = GitHubRepository("owner/repo"),
    )

    @Test
    fun `includes diff in prompt`() {
        val prompt = buildPrompt(event, "diff --git a/file b/file")

        assertTrue(prompt.contains("```diff\ndiff --git a/file b/file\n```"))
    }

    @Test
    fun `marks unavailable diff`() {
        val prompt = buildPrompt(event, null)

        assertTrue(prompt.contains("Diff: unavailable"))
    }

    @Test
    fun `handles missing optional fields`() {
        val sparse = GitHubPullRequestEvent(
            action = "opened",
            number = 42,
            pullRequest = GitHubPullRequest(title = null),
            repository = GitHubRepository("owner/repo"),
        )

        val prompt = buildPrompt(sparse, null)

        assertTrue(prompt.contains("Author: (unknown)"))
    }
}
