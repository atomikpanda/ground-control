package com.atomikpanda.groundcontrol.notify

import android.content.Context

object WatchController {
    fun enable(context: Context) {
        WatchService.start(context)
        WatchBackstopWorker.enqueue(context)
    }
    fun disable(context: Context) {
        WatchService.stop(context)
        WatchBackstopWorker.cancel(context)
    }
}
