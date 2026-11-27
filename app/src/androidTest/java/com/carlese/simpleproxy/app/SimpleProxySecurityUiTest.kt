package com.carlese.simpleproxy.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.carlese.simpleproxy.app.data.SettingsRepository
import com.carlese.simpleproxy.app.ui.stringsFor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleProxySecurityUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun editorRequiresNonEmptyPortAndHostBeforeSaving() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val strings = stringsFor(SettingsRepository.getInstance(ctx).settings.value.language)

        composeRule.onNode(hasContentDescription(strings.addProxy) or hasText(strings.addProxy)).performClick()
        composeRule.onNodeWithText(strings.save).assertIsNotEnabled()

        composeRule.onNodeWithText(strings.hostLabel).performTextInput("192.0.2.10")
        composeRule.onNodeWithText(strings.save).assertIsNotEnabled()

        composeRule.onNodeWithText(strings.portLabel).performTextInput("1080")
        composeRule.onNodeWithText(strings.save).assertIsEnabled()

        composeRule.onNodeWithContentDescription(strings.cancel).performClick()
    }

    @Test
    fun optionsScreenDisplaysAlphabeticalLanguageDropdown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val strings = stringsFor(SettingsRepository.getInstance(ctx).settings.value.language)

        composeRule.onNodeWithContentDescription(strings.options).performClick()
        composeRule.onNodeWithText(strings.language).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(strings.language).performClick()
        composeRule.onAllNodesWithText("English", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("Français", substring = true).onFirst().assertIsDisplayed()
    }
}
