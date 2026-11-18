package com.aircursor.core

import com.aircursor.core.RemoteInput.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPumpTest {
    @Test
    fun movesMergeAndOrderIsKept() {
        val p = InputPump()
        assertTrue("first command asks for a drain", p.offer(Cmd.Move(1f, 1f)))
        assertFalse("already asked", p.offer(Cmd.Move(2f, 3f)))
        p.offer(Cmd.Click('l'))
        p.offer(Cmd.Move(4f, 0f))
        p.offer(Cmd.Ping)
        p.offer(Cmd.Key(Keysyms.TAB))
        val got = ArrayList<Cmd>()
        p.drain { got += it }
        assertEquals(listOf(Cmd.Move(3f, 4f), Cmd.Click('l'), Cmd.Move(4f, 0f), Cmd.Key(Keysyms.TAB)), got)
        assertTrue("after a drain the next command asks again", p.offer(Cmd.Move(1f, 0f)))
    }

    @Test
    fun commandsAddedWhileDrainingAreHandledToo() {
        val p = InputPump()
        p.offer(Cmd.Key(1000))
        val got = ArrayList<Cmd>()
        p.drain {
            got += it
            if (got.size == 1) assertFalse(p.offer(Cmd.Key(1001)))
        }
        assertEquals(listOf<Cmd>(Cmd.Key(1000), Cmd.Key(1001)), got)
    }
}
