package com.example.core

import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A long-lived scope for a manager object. One failed task neither cancels the scope (later work
 * would silently never run) nor crashes the app: the failure is logged, and so becomes a
 * structured error event.
 */
fun managerScope(tag: String, dispatcher: CoroutineDispatcher = Dispatchers.IO): CoroutineScope =
    CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        XrayLogManager.e(tag, "Background task failed: ${e.javaClass.simpleName}: ${e.message}", e)
    })
