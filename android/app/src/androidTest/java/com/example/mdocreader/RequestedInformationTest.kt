package com.example.mdocreader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.mdocreader.theme.MdocReaderTheme

class RequestedInformationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkboxControlsSelectionAndStartAvailability() {
        val field = RequestedField(0, "one", "portrait")
        val selected = mutableStateOf(setOf(field))
        compose.setContent {
            MdocReaderTheme {
                ReaderScreen(ReaderState(), {}, {}, {}, {}, listOf(field), selected.value,
                    { item, checked -> selected.value = if (checked) selected.value + item else selected.value - item })
            }
        }
        compose.onNodeWithText("Portrait").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("Portrait").assertIsOff()
        compose.onNodeWithText("Start reading").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Portrait").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("Start reading").performScrollTo().assertIsEnabled()
    }

    private val template = """{
      "iacaCert":"https://example.com/cert", "docRequests":[
        {"itemsRequest":{"docType":"first","nameSpaces":{"one":{"name":false,"portrait":true},"two":{"age":false}}}},
        {"itemsRequest":{"docType":"second","nameSpaces":{"one":{"name":false}}}}
      ]
    }"""

    @Test fun selectionRemovesOtherFieldsNamespacesAndDocumentsAndPreservesConfiguration() {
        val chosen = RequestedField(0, "one", "portrait")
        assertEquals(4, requestedFields(template).size)
        val result = JSONObject(selectedRequest(template, setOf(chosen)))
        assertEquals("https://example.com/cert", result.getString("iacaCert"))
        val documents = result.getJSONArray("docRequests")
        assertEquals(1, documents.length())
        val items = documents.getJSONObject(0).getJSONObject("itemsRequest")
        assertEquals("first", items.getString("docType"))
        val namespaces = items.getJSONObject("nameSpaces")
        assertEquals(1, namespaces.length())
        val fields = namespaces.getJSONObject("one")
        assertEquals(1, fields.length())
        assertTrue(fields.getBoolean("portrait"))
        assertEquals(4, requestedFields(template).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySelectionIsRejected() {
        selectedRequest(template, emptySet())
    }
}
