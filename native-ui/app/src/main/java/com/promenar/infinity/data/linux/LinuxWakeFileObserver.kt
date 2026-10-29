package com.promenar.infinity.data.linux

import android.os.FileObserver
import java.io.File

class LinuxWakeFileObserver : LinuxWakeWatcher {
    override fun watch(directory: File, onChange: () -> Unit): AutoCloseable {
        val observer = object : FileObserver(directory, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (event and (MOVED_TO or CLOSE_WRITE) != 0) onChange()
            }
        }
        observer.startWatching()
        return AutoCloseable { observer.stopWatching() }
    }
}
