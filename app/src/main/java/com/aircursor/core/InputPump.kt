package com.aircursor.core

import com.aircursor.core.RemoteInput.Cmd

/**
 * Commands from the other phone, on their way from the network thread to the screen thread.
 *
 * The screen thread is often busy drawing the PC picture; one message per move would wait behind
 * it and arrive in clumps. Here moves that follow each other are merged, and the screen thread takes
 * everything waiting in one go (asked at the front of its queue), in the order it came.
 */
class InputPump {
    private val items = ArrayDeque<Cmd>()
    private var scheduled = false

    /** Adds a command. Returns true when the screen thread must be asked to [drain]. */
    @Synchronized
    fun offer(c: Cmd): Boolean {
        val last = items.lastOrNull()
        if (c is Cmd.Move && last is Cmd.Move) {
            items[items.size - 1] = Cmd.Move(last.dx + c.dx, last.dy + c.dy)
        } else if (c is Cmd.Scroll && last is Cmd.Scroll) {
            items[items.size - 1] = Cmd.Scroll(last.dx + c.dx, last.dy + c.dy)
        } else if (c != Cmd.Ping) {
            items.addLast(c)
        }
        if (scheduled || items.isEmpty()) return false
        scheduled = true
        return true
    }

    /** On the screen thread: hands over everything waiting, oldest first. */
    fun drain(handle: (Cmd) -> Unit) {
        while (true) {
            val batch = synchronized(this) {
                if (items.isEmpty()) {
                    scheduled = false
                    return
                }
                val b = items.toList()
                items.clear()
                b
            }
            for (c in batch) handle(c)
        }
    }
}
