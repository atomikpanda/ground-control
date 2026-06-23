package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThreadSummary(
    val id: String,
    val subject: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("awaiting_reply") val awaitingReply: Boolean = false,
    @SerialName("last_message") val lastMessage: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
)

@Serializable
data class Message(
    val id: String,
    @SerialName("thread_id") val threadId: String = "",
    val role: String,
    val text: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class Thread(
    val id: String,
    val subject: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("task_slug") val taskSlug: String? = null,
    @SerialName("spec_id") val specId: String? = null,
    val messages: List<Message> = emptyList(),
    @SerialName("awaiting_reply") val awaitingReply: Boolean = false,
)

@Serializable data class NewThreadBody(val text: String, val subject: String? = null)
@Serializable data class NewMessageBody(val text: String)
