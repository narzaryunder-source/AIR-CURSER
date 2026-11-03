package com.aircursor.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantBrainTest {
    private fun p(s: String, name: String = "Air") = AssistantBrain.parse(s, name)

    @Test
    fun opensAppsInEnglishAndHinglish() {
        assertEquals(Command.Open("youtube"), p("Hey Air, open YouTube"))
        assertEquals(Command.Open("youtube"), p("YouTube kholo"))
        assertEquals(Command.Open("youtube"), p("youtube open karo"))
        assertEquals(Command.Open("air store"), p("open the Air Store"))
        assertEquals(Command.Open("vs code"), p("VS Code open kar do"))
        assertEquals(Command.Open("files"), p("files kholo please"))
        assertEquals(Command.Open("spotify"), p("start spotify"))
        assertEquals(Command.Open("github.com"), p("go to github.com"))
    }

    @Test
    fun customNameAndWakeWordsAreIgnored() {
        assertEquals(Command.Open("gmail"), p("Jarvis open gmail", name = "Jarvis"))
        assertEquals(Command.Open("gmail"), p("Friday, gmail kholo", name = "Friday"))
        assertEquals(Command.Open("gmail"), p("ok tony open gmail", name = "Tony"))
    }

    @Test
    fun searches() {
        assertEquals(Command.Search("weather in delhi"), p("search weather in Delhi"))
        assertEquals(Command.Search("iphone 17 price"), p("iPhone 17 price search karo"))
        assertEquals(Command.Search("black holes"), p("black holes ke baare mein batao"))
        assertEquals(Command.Search("who is the prime minister of india"), p("Who is the Prime Minister of India?"))
        assertEquals(Command.Search("this"), p("Jarvis search this", name = "Jarvis"))
    }

    @Test
    fun youtube() {
        assertEquals(Command.PlayYouTube("despacito"), p("play despacito"))
        assertEquals(Command.PlayYouTube("lofi music"), p("YouTube pe lofi music chalao"))
        assertEquals(Command.PlayYouTube("arijit singh songs"), p("Arijit Singh ke gaane chalao"))
        assertEquals(Command.PlayYouTube("kesariya"), p("kesariya bajao"))
        assertEquals(Command.PlayYouTube("cat videos"), p("open youtube and play cat videos"))
        assertEquals(Command.PlayYouTube("minecraft"), p("play minecraft on youtube"))
    }

    @Test
    fun switchesFeatures() {
        assertEquals(Command.Toggle("hand", true), p("hand control on karo"))
        assertEquals(Command.Toggle("fps", false), p("turn off the FPS meter"))
        assertEquals(Command.Toggle("torch", true), p("torch on"))
        assertEquals(Command.Toggle("wifi", false), p("wifi band karo"))
        assertEquals(Command.Toggle("game", true), p("start game mode"))
        assertEquals(Command.Toggle("shields", false), p("disable ad blocker"))
        assertEquals(Command.Toggle("dark", true), p("dark mode on kar do"))
        assertEquals(Command.CloseApp("youtube"), p("YouTube band karo"))
    }

    @Test
    fun typingScrollingTabsWindows() {
        assertEquals(Command.Type("hello world"), p("type hello world"))
        assertEquals(Command.Type("namaste dosto"), p("namaste dosto likho"))
        assertEquals(Command.Scroll(true), p("scroll down"))
        assertEquals(Command.Scroll(true), p("neeche"))
        assertEquals(Command.Scroll(false), p("upar"))
        assertEquals(Command.Browser("newtab"), p("naya tab kholo"))
        assertEquals(Command.Browser("back"), p("peeche jao"))
        assertEquals(Command.Browser("reload"), p("refresh"))
        assertEquals(Command.Window("minimize"), p("minimize"))
        assertEquals(Command.Window("close"), p("band karo"))
        assertEquals(Command.Window("desktop"), p("show desktop"))
    }

    @Test
    fun infoAndSystem() {
        assertEquals(Command.Time, p("what's the time"))
        assertEquals(Command.Time, p("time kya hua hai"))
        assertEquals(Command.Date, p("aaj ki date kya hai"))
        assertEquals(Command.Battery, p("battery kitni hai"))
        assertEquals(Command.Screenshot, p("screenshot lo"))
        assertEquals(Command.Lock, p("lock the PC"))
        assertEquals(Command.Volume("up"), p("volume badhao"))
        assertEquals(Command.Volume("down"), p("awaz kam karo"))
        assertEquals(Command.Volume("mute"), p("mute"))
        assertEquals(Command.Help, p("what can you do"))
        assertEquals(Command.Rename("Friday"), p("tumhara naam Friday hai"))
        assertEquals(Command.Rename("Jarvis"), p("your name is jarvis"))
        assertEquals(Command.Nothing, p("   "))
    }
}
