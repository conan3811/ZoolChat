package com.zoolchat.app.realtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

object RealtimeEventBus {
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()
    fun emit(event: JSONObject) { _events.tryEmit(event) }
}
