package com.kriyasense.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small screen history shared by the authenticated and login flows. */
internal class ScreenBackStack(root: String) {
    private var entries by mutableStateOf(listOf(root))
    val screen: String get() = entries.last()
    val canGoBack: Boolean get() = entries.size > 1

    fun navigateTo(destination: String) {
        if (destination == screen) return
        // A completed workout must never return to its camera or tutorial.
        if (destination == "results") {
            val selection = entries.indexOfLast { it == "select" }
            entries = if (selection >= 0) entries.take(selection + 1)
                else listOf(entries.first(), "select")
        }
        entries = entries + destination
    }

    fun goBack() {
        if (canGoBack) entries = entries.dropLast(1)
    }
}
