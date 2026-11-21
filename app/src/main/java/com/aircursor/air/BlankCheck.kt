package com.aircursor.air

/**
 * Decides whether an Air window is showing nothing (the black-window bug) from a few pixels of
 * its page surface, and what to do about it.
 */
internal object BlankCheck {
    enum class Result {
        /** The page shows something. */
        FINE,

        /** Only the window's own background colour: Gecko drew nothing on this surface. */
        CLEAR,

        /** All black: a broken surface – or a page that is black on purpose (a dark video). */
        BLACK,

        /** The surface has no picture at all. */
        NOTHING,
    }

    enum class Step { NEW_VIEW, RELOAD, GIVE_UP }

    /** [px]: pixels of the page surface (any size); [clear]: the window's background colour. */
    fun judge(px: IntArray, clear: Int = AirBrowser.PAGE_BG): Result {
        if (px.isEmpty()) return Result.NOTHING
        // Pixels never drawn on a TextureView are fully transparent: the same as black here.
        val first = px[0] or OPAQUE
        for (p in px) if ((p or OPAQUE) != first) return Result.FINE
        return when (first and RGB) {
            clear and RGB -> Result.CLEAR
            0 -> Result.BLACK
            else -> Result.FINE   // one colour all over (a white page loading): drawn, fine
        }
    }

    /**
     * What to do after the [tries]-th failed look: a new view twice (cheap, keeps the page as it
     * is), then one reload – but never for an all-black page, which may be a dark video.
     */
    fun next(tries: Int, r: Result): Step = when {
        r == Result.FINE -> Step.GIVE_UP
        tries <= 2 -> Step.NEW_VIEW
        tries == 3 && r != Result.BLACK -> Step.RELOAD
        else -> Step.GIVE_UP
    }

    private const val OPAQUE = 0xFF000000.toInt()
    private const val RGB = 0x00FFFFFF
}
