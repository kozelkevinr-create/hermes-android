package com.hermes.client.notifications

import com.hermes.client.data.ntfy.NtfyApproval
import com.hermes.client.data.ntfy.NtfyMessage
import com.hermes.client.ui.chat.ApprovalChoice

/** Notification id for a ntfy message, so a withdrawal can clear exactly its notification. */
fun ntfyNotificationId(messageId: String): Int = notificationId("ntfy", messageId)

/**
 * Maps a ntfy message to a notification. An approval gets the same Allow once / Session / Deny buttons
 * as a gateway approval (only those the host allows; ntfy and Android both cap the useful count at
 * three); anything else is shown as a plain alert. Null when the user turned the matching alerts off.
 */
fun ntfyNotificationSpec(message: NtfyMessage, approval: NtfyApproval?, prefs: NotificationPrefs): NotificationSpec? {
    if (!prefs.enabled) return null
    val id = ntfyNotificationId(message.id)
    if (approval != null) {
        if (!prefs.approvals) return null
        fun act(label: String, choice: ApprovalChoice, action: String) =
            if (choice in approval.allowed) NotifAction(label, action, Notif.NTFY_SESSION, requestId = message.id) else null
        return NotificationSpec(
            id = id,
            channelId = Notif.CHANNEL_APPROVALS,
            title = "Approval needed",
            body = approval.description.ifBlank { approval.command }.ifBlank { "The agent is waiting for your approval." },
            route = "approvals",
            actions = listOfNotNull(
                act("Allow once", ApprovalChoice.ONCE, Notif.ACTION_ALLOW_ONCE),
                act("Session", ApprovalChoice.SESSION, Notif.ACTION_ALLOW_SESSION),
                act("Deny", ApprovalChoice.DENY, Notif.ACTION_DENY),
            ),
            groupKey = "approval",
        )
    }
    return NotificationSpec(
        id = id,
        channelId = Notif.CHANNEL_NTFY,
        title = message.title.ifBlank { "Hermes" },
        body = message.message,
        route = null,
        actions = emptyList(),
        groupKey = "ntfy",
    )
}
