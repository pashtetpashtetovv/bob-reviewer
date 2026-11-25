package com.pashtetovv.bob_reviewer.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubPullRequestEvent(
    val action: String,
    val number: Int,
    @param:JsonProperty("pull_request") val pullRequest: GitHubPullRequest,
    val repository: GitHubRepository,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubPullRequest(
    val title: String? = null,
    val body: String? = null,
    val draft: Boolean? = false,
    @param:JsonProperty("html_url") val htmlUrl: String? = null,
    val user: GitHubUser? = null,
    val head: GitHubRef? = null,
    val base: GitHubRef? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubRepository(
    @param:JsonProperty("full_name") val fullName: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubUser(val login: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubRef(val sha: String, val ref: String)
