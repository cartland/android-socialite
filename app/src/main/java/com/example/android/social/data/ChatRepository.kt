/*
 * Copyright (C) 2019 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.social.data

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.Executor
import java.util.concurrent.Executors

interface ChatRepository {
    fun getContacts(): Flow<List<Contact>>
    fun findContact(id: Long): Flow<Contact?>
    fun findMessages(id: Long): Flow<List<Message>>
    fun sendMessage(id: Long, text: String, photoUri: Uri?, photoMimeType: String?)
    fun updateNotification(id: Long)
    fun activateChat(id: Long)
    fun deactivateChat(id: Long)
    fun showAsBubble(id: Long)
    fun canBubble(id: Long): Boolean
}

class DefaultChatRepository internal constructor(
    private val notificationHelper: NotificationHelper,
    private val executor: Executor,
) : ChatRepository {

    companion object {
        private var instance: DefaultChatRepository? = null

        fun getInstance(context: Context): DefaultChatRepository {
            return instance ?: synchronized(this) {
                instance ?: DefaultChatRepository(
                    NotificationHelper(context),
                    Executors.newFixedThreadPool(4),
                ).also {
                    instance = it
                }
            }
        }
    }

    private var currentChat: Long = 0L

    private val chats = Contact.CONTACTS.map { contact ->
        contact.id to Chat(contact)
    }.toMap()

    init {
        notificationHelper.setUpNotificationChannels()
    }

    @MainThread
    override fun getContacts(): Flow<List<Contact>> {
        return flowOf(Contact.CONTACTS)
    }

    @MainThread
    override fun findContact(id: Long): Flow<Contact?> {
        return flowOf(Contact.CONTACTS.find { it.id == id })
    }

    @MainThread
    override fun findMessages(id: Long): Flow<List<Message>> {
        return callbackFlow {
            val listener: ChatThreadListener = { messages -> trySend(messages) }
            val chat = chats.getValue(id)
            chat.addListener(listener)
            trySend(chat.messages)
            awaitClose { chat.removeListener { } }
        }
    }

    @MainThread
    override fun sendMessage(id: Long, text: String, photoUri: Uri?, photoMimeType: String?) {
        val chat = chats.getValue(id)
        chat.addMessage(
            Message.Builder().apply {
                sender = 0L // User
                this.text = text
                timestamp = System.currentTimeMillis()
                this.photo = photoUri
                this.photoMimeType = photoMimeType
            },
        )
        executor.execute {
            // The animal is typing...
            Thread.sleep(5000L)
            // Receive a reply.
            chat.addMessage(chat.contact.reply(text))
            // Show notification if the chat is not on the foreground.
            if (chat.contact.id != currentChat) {
                notificationHelper.showNotification(chat, false)
            }
        }
    }

    override fun updateNotification(id: Long) {
        val chat = chats.getValue(id)
        notificationHelper.showNotification(chat, fromUser = false, update = true)
    }

    override fun activateChat(id: Long) {
        val chat = chats.getValue(id)
        currentChat = id
        notificationHelper.updateNotification(chat, id, !chat.isModified)
    }

    override fun deactivateChat(id: Long) {
        if (currentChat == id) {
            currentChat = 0
        }
    }

    override fun showAsBubble(id: Long) {
        val chat = chats.getValue(id)
        executor.execute {
            notificationHelper.showNotification(chat, true)
        }
    }

    override fun canBubble(id: Long): Boolean {
        val chat = chats.getValue(id)
        return notificationHelper.canBubble(chat.contact)
    }
}
