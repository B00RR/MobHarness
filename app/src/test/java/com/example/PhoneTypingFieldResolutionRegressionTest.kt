package com.example

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.phoneuse.MobHarnAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneTypingFieldResolutionRegressionTest {

    @Test
    fun `two-field form with focus on To resolves and fills Subject when hint is supplied`() {
        val service = Robolectric.buildService(MobHarnAccessibilityService::class.java).get()

        val root = AccessibilityNodeInfo.obtain()
        val toNode = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            hintText = "To"
            text = "initial@recipient.com"
        }
        val subjectNode = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            hintText = "Subject"
            text = ""
        }
        root.addChild(toNode)
        root.addChild(subjectNode)

        var targetedNode: AccessibilityNodeInfo? = null
        var setActionCalled = false
        var textDelivered: CharSequence? = null

        val actionPerformer: (AccessibilityNodeInfo, Int, Bundle?) -> Boolean = { node, action, bundle ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                setActionCalled = true
                targetedNode = node
                textDelivered = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
            }
            true
        }

        // Focus is on "To", but caller asks to type into "Subject"
        val outcome = service.typeTextInternal(
            root = root,
            fieldHint = "Subject",
            textToType = "Project Status Update",
            actionPerformer = actionPerformer,
            focusedNodeResolver = { toNode }
        )

        assertTrue("ACTION_SET_TEXT must have been performed", setActionCalled)
        assertEquals("Subject node must be targeted, NOT the focused To node", subjectNode, targetedNode)
        assertEquals("Project Status Update", textDelivered?.toString())
        assertEquals("Testo inserito nel campo 'Subject'.", outcome)
        assertFalse("Outcome trace must not contain the typed private value", outcome.contains("Project Status Update"))
    }

    @Test
    fun `two-field form fails and does not fill focused input when hint does not match`() {
        val service = Robolectric.buildService(MobHarnAccessibilityService::class.java).get()

        val root = AccessibilityNodeInfo.obtain()
        val toNode = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            hintText = "To"
        }
        val subjectNode = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            hintText = "Subject"
        }
        root.addChild(toNode)
        root.addChild(subjectNode)

        var actionAttempted = false
        val actionPerformer: (AccessibilityNodeInfo, Int, Bundle?) -> Boolean = { _, _, _ ->
            actionAttempted = true
            true
        }

        // Caller asks for "Body" which does not exist, while "To" is focused
        val outcome = service.typeTextInternal(
            root = root,
            fieldHint = "Body",
            textToType = "Confidential Note",
            actionPerformer = actionPerformer,
            focusedNodeResolver = { toNode }
        )

        assertFalse("Must NOT perform action on wrong field when hint does not match", actionAttempted)
        assertEquals("Campo di testo 'Body' non trovato sullo schermo.", outcome)
    }

    @Test
    fun `two-field form falls back to focused input when no hint is supplied`() {
        val service = Robolectric.buildService(MobHarnAccessibilityService::class.java).get()

        val root = AccessibilityNodeInfo.obtain()
        val toNode = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            hintText = "To"
        }
        val subjectNode = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            hintText = "Subject"
        }
        root.addChild(toNode)
        root.addChild(subjectNode)

        var targetedNode: AccessibilityNodeInfo? = null
        val actionPerformer: (AccessibilityNodeInfo, Int, Bundle?) -> Boolean = { node, action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                targetedNode = node
            }
            true
        }

        // No hint provided (empty string): should pick the focused input ("To")
        val outcome = service.typeTextInternal(
            root = root,
            fieldHint = "",
            textToType = "recipient@example.com",
            actionPerformer = actionPerformer,
            focusedNodeResolver = { toNode }
        )

        assertEquals("Should target the focused To node when fieldHint is empty", toNode, targetedNode)
        assertEquals("Testo inserito nel campo attivo.", outcome)
        assertFalse(outcome.contains("recipient@example.com"))
    }

    @Test
    fun `findEditableByQuery returns null when query not found and does not fallback to first editable`() {
        val service = Robolectric.buildService(MobHarnAccessibilityService::class.java).get()

        val root = AccessibilityNodeInfo.obtain()
        val toNode = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            hintText = "To"
        }
        val subjectNode = AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            hintText = "Subject"
        }
        root.addChild(toNode)
        root.addChild(subjectNode)

        val matchSubject = service.findEditableByQuery(root, "Subject")
        assertNotNull(matchSubject)
        assertEquals(subjectNode, matchSubject)

        val matchNonExistent = service.findEditableByQuery(root, "Password")
        assertNull("Must return null when query does not match any editable node", matchNonExistent)
    }
}
