package com.kriyasense.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(root: String = "landing"): ScreenBackStack {
        val navigation = ScreenBackStack(root)
        rule.setContent {
            BackHandler(enabled = navigation.canGoBack) { navigation.goBack() }
            Text(navigation.screen)
        }
        return navigation
    }

    private fun backTo(screen: String) {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText(screen).assertExists()
    }

    @Test fun systemBackRetracesExerciseFlow() {
        val navigation = show()
        rule.runOnIdle {
            listOf("profile", "select", "variants", "tutorial", "camera").forEach(navigation::navigateTo)
        }
        listOf("tutorial", "variants", "select", "profile", "landing").forEach(::backTo)
        rule.runOnIdle { assertFalse(navigation.canGoBack) }
    }

    @Test fun tutorialWithoutVariantsReturnsToSelection() {
        val navigation = show()
        rule.runOnIdle { listOf("select", "tutorial").forEach(navigation::navigateTo) }
        backTo("select")
    }

    @Test fun historyReturnsToEachOpenerAndIgnoresDuplicates() {
        val navigation = show()
        listOf("landing", "profile", "select", "variants", "tutorial", "camera", "results").forEach { opener ->
            rule.runOnIdle {
                navigation.navigateTo(opener)
                navigation.navigateTo("history")
                navigation.navigateTo("history")
                navigation.navigateTo("historyDetail")
            }
            backTo("history")
            backTo(opener)
        }
    }

    @Test fun completedWorkoutSkipsCameraAndTutorial() {
        val navigation = show()
        rule.runOnIdle {
            listOf("profile", "select", "variants", "tutorial", "camera", "results").forEach(navigation::navigateTo)
        }
        backTo("select")
        backTo("profile")
    }

    @Test fun signupReturnsToRootLogin() {
        val navigation = show("login")
        rule.runOnIdle { navigation.navigateTo("signup") }
        backTo("login")
        rule.runOnIdle {
            navigation.goBack()
            assertEquals("login", navigation.screen)
            assertFalse(navigation.canGoBack)
        }
    }
}
