package com.opentune.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageOpenerTest {
    /** A back stack of entry ids, as the navigation controller keeps one. */
    private class Stack {
        val routes = mutableListOf("home")
        private val ids = mutableListOf("e0")
        private var next = 1
        fun top() = ids.lastOrNull()
        fun push(route: String) { routes += route; ids += "e${next++}" }
        fun pop() { routes.removeAt(routes.lastIndex); ids.removeAt(ids.lastIndex) }
    }

    @Test fun aDoubleTapOpensThePageOnce() {
        val stack = Stack()
        val pages = PageOpener(stack::top, stack::push)
        pages.open("liked")
        pages.open("liked")
        assertEquals(listOf("home", "liked"), stack.routes)
    }

    @Test fun backThenTheSameTapOpensItAgain() {
        val stack = Stack()
        val pages = PageOpener(stack::top, stack::push)
        pages.open("playlist/a")
        stack.pop()
        pages.open("playlist/a")
        assertEquals(listOf("home", "playlist/a"), stack.routes)
    }

    @Test fun anotherPageOpensOverTheOneJustOpened() {
        val stack = Stack()
        val pages = PageOpener(stack::top, stack::push)
        pages.open("settings")
        pages.open("equalizer")
        pages.open("equalizer")
        assertEquals(listOf("home", "settings", "equalizer"), stack.routes)
    }

    @Test fun aPageThatClosesItselfCanBeOpenedAgain() {
        // Sign-in's page starts the sign-in screen and takes itself off the stack.
        val stack = Stack()
        var opened = 0
        val pages = PageOpener(stack::top) { route -> opened++; stack.push(route); if (route == "login") stack.pop() }
        pages.open("login")
        pages.open("login")
        assertEquals(2, opened)
        assertEquals(listOf("home"), stack.routes)
    }
}
