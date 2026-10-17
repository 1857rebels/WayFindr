package com.example.wayfindr.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the current incoming/active call, fed by [WayfindrNotificationListener] (which sees the
 * dialer's CATEGORY_CALL notification and its caller-name title). A process-wide singleton because
 * the listener is a system-instantiated service we can't hand a reference to — it pushes here, the
 * dash session observes [incomingCall].
 *
 * This deliberately avoids READ_PHONE_STATE / READ_CALL_LOG / READ_CONTACTS: the caller name we need
 * is already in the notification the system shows, and notification access is granted anyway for the
 * now-playing feature. The dash only ever DISPLAYS the call (Android can't answer/end it for us).
 */
object CallInfoProvider {
    private val _incomingCall = MutableStateFlow<IncomingCall?>(null)
    val incomingCall: StateFlow<IncomingCall?> = _incomingCall.asStateFlow()

    /** Called by the notification listener. Pass null when the call notification clears. */
    fun update(call: IncomingCall?) { _incomingCall.value = call }
}
