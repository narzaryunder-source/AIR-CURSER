package com.aircursor.cloudpc

/**
 * Commands for a PC window from the phone (menu bar buttons, the app menu, the dock). Full size and
 * close are window-manager messages (win-place.py), not keyboard shortcuts: those depend on the
 * PC's shortcut settings and on which window has the keyboard.
 *
 * Window ids come from /tmp/cloudpc-windows, a file in the PC: anything that is not a plain X window
 * id gives no command at all (it ends up in a shell command).
 */
internal object PcWindowCommands {
    private const val HELPER = "python3 -I /usr/local/lib/cloudpc/win-place.py"
    private val ID = Regex("0x[0-9a-fA-F]{1,8}")

    fun valid(id: String) = ID.matches(id)

    fun minimize(id: String) = if (valid(id)) "xdotool windowminimize $id" else null

    fun toggleFullSize(id: String) = if (valid(id)) "$HELPER --toggle-max $id" else null

    fun close(id: String) = if (valid(id)) "$HELPER --close $id" else null

    /** Closes several windows (one app's) in one go. */
    fun closeAll(ids: List<String>) = ids.filter(::valid).takeIf { it.isNotEmpty() }?.let { "$HELPER --close ${it.joinToString(" ")}" }

    fun activate(id: String) = if (valid(id)) "xdotool windowactivate $id" else null
}
