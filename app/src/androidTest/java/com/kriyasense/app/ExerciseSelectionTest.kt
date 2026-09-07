package com.kriyasense.app

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test

class ExerciseSelectionTest {
    @get:Rule val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun allEightExercisesAreAvailableInScrollableGrid() {
        listOf("SQUAT","LUNGE","PUSH_UP","BICEP_CURL","SHOULDER_PRESS","CALF_RAISE","PLANK","JUMPING_JACK").forEach {
            rule.onNodeWithTag("exercise_grid").performScrollToNode(hasTestTag("exercise_$it"))
            rule.onNodeWithTag("exercise_$it").assertExists()
        }
        rule.onNodeWithTag("exercise_grid").assertExists()
    }

    @Test fun selectingExerciseUpdatesVisibleSelection() {
        rule.onNodeWithTag("exercise_grid").performScrollToNode(hasTestTag("exercise_SHOULDER_PRESS"))
        rule.onNodeWithTag("exercise_SHOULDER_PRESS").performClick()
        rule.onNodeWithTag("selected_exercise_name").assertExists()
        rule.onNodeWithText("Shoulder Press").assertExists()
        rule.onNodeWithTag("selected_exercise_id").assertExists()
    }

    @Test fun startCameraIsVisibleAndNavigatesForSelectedExercise() {
        rule.onNodeWithTag("exercise_grid").performScrollToNode(hasTestTag("exercise_PLANK"))
        rule.onNodeWithTag("exercise_PLANK").performClick()
        rule.onNodeWithTag("start_camera").assertIsDisplayed().performClick()
        rule.onNodeWithText("Plank • Live analysis").assertExists()
    }
}
