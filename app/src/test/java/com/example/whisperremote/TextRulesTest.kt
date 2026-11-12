// This file verifies Kotlin text rules against canonical server test fixtures.
// It is called by the unit test runner (`./gradlew testFullDebugUnitTest`).
// Inputs: contracts/05-text-rules-tests.json (generated from api/fillers.py and client/transcribe_client.py).
// Outputs: Validates that TextRules.removeFillers and TextRules.applyNameCorrections match server behavior 100%.
// Strictly conforms to contracts/05-on-device.md §2.4.
// Reason for creation: Verify Kotlin text rules against contracts/05-text-rules-tests.json per F-28.

package com.example.whisperremote

import com.example.whisperremote.util.TextRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class TextRulesTest {

    private lateinit var json: JSONObject

    @Before
    fun setup() {
        val candidates = listOf(
            File("../contracts/05-text-rules-tests.json"),
            File("contracts/05-text-rules-tests.json")
        )
        val file = candidates.find { it.exists() }
            ?: throw IllegalStateException("contracts/05-text-rules-tests.json not found from ${File(".").absolutePath}")
        json = JSONObject(file.readText())
    }

    @Test
    fun testAllFillerCasesFromContract() {
        val fillersArray = json.getJSONArray("fillers")
        for (i in 0 until fillersArray.length()) {
            val caseObj = fillersArray.getJSONObject(i)
            val input = caseObj.getString("input")
            val expectedOutput = caseObj.getString("output")

            val (actualOutput, _) = TextRules.removeFillers(input)
            assertEquals("Filler test case $i failed for input '$input'", expectedOutput, actualOutput)
        }
    }

    @Test
    fun testAllNameCorrectionCasesFromContract() {
        val nameCorrectionsObj = json.getJSONObject("name_corrections")
        val correctionsMapObj = nameCorrectionsObj.getJSONObject("corrections")
        val corrections = mutableMapOf<String, String>()
        for (key in correctionsMapObj.keys()) {
            corrections[key] = correctionsMapObj.getString(key)
        }

        val casesArray = nameCorrectionsObj.getJSONArray("cases")
        for (i in 0 until casesArray.length()) {
            val caseObj = casesArray.getJSONObject(i)
            val input = caseObj.getString("input")
            val expectedOutput = caseObj.getString("output")

            val actualOutput = TextRules.applyNameCorrections(input, corrections)
            assertEquals("Name correction test case $i failed for input '$input'", expectedOutput, actualOutput)
        }
    }
}
