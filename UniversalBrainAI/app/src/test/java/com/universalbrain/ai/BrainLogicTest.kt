package com.universalbrain.ai

import com.universalbrain.ai.domain.ai.CommandParser
import com.universalbrain.ai.domain.ai.DecisionEngine
import com.universalbrain.ai.domain.ai.IntentAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI command processing: parser, intent analysis, plan generation. */
class BrainLogicTest {

    private fun analyze(raw: String): IntentAnalyzer.Analysis =
        IntentAnalyzer.analyze(CommandParser.parse(raw))

    @Test
    fun `parser normalizes punctuation and case`() {
        val parsed = CommandParser.parse("  Show ALL my ACCOUNTS!!! ")
        assertEquals("show all my accounts", parsed.normalized)
        assertTrue(parsed.mentionsAll)
    }

    @Test
    fun `show accounts intent`() {
        assertEquals(IntentAnalyzer.Intent.SHOW_ACCOUNTS, analyze("Show all my accounts.").intent)
        assertEquals(IntentAnalyzer.Intent.SHOW_ACCOUNTS, analyze("list accounts").intent)
    }

    @Test
    fun `mining performance intent`() {
        assertEquals(IntentAnalyzer.Intent.MINING_PERFORMANCE, analyze("Check mining performance").intent)
        assertEquals(IntentAnalyzer.Intent.MINING_PERFORMANCE, analyze("what is my hashrate").intent)
    }

    @Test
    fun `earnings intent detects today`() {
        val analysis = analyze("Show today's earnings")
        assertEquals(IntentAnalyzer.Intent.SHOW_EARNINGS, analysis.intent)
        assertTrue(analysis.mentionsToday)
    }

    @Test
    fun `optimize intent`() {
        assertEquals(IntentAnalyzer.Intent.OPTIMIZE_MINING, analyze("Optimize the mining configuration").intent)
    }

    @Test
    fun `pause mining on temperature intent`() {
        assertEquals(IntentAnalyzer.Intent.PAUSE_MINING_ON_TEMP, analyze("Pause mining when temperature becomes high").intent)
    }

    @Test
    fun `report intent`() {
        assertEquals(IntentAnalyzer.Intent.GENERATE_REPORT, analyze("Generate a complete performance report").intent)
    }

    @Test
    fun `device status intent`() {
        assertEquals(IntentAnalyzer.Intent.DEVICE_STATUS, analyze("check my cpu and ram").intent)
    }

    @Test
    fun `open instances is disambiguated from show instances`() {
        assertEquals(IntentAnalyzer.Intent.OPEN_INSTANCES, analyze("Open all active instances").intent)
        assertEquals(IntentAnalyzer.Intent.SHOW_INSTANCES, analyze("show my instances").intent)
    }

    @Test
    fun `unknown command has zero confidence`() {
        val analysis = analyze("tell me a joke about penguins")
        assertEquals(IntentAnalyzer.Intent.UNKNOWN, analysis.intent)
        assertEquals(0.0, analysis.confidence, 0.001)
    }

    @Test
    fun `destructive plans require confirmation`() {
        val openPlan = DecisionEngine.plan(analyze("Open all active instances"))
        assertTrue(openPlan.steps.any { it.requiresConfirmation })

        val showPlan = DecisionEngine.plan(analyze("show my instances"))
        assertFalse(showPlan.steps.any { it.requiresConfirmation })
    }

    @Test
    fun `temperature pause plan creates automation rule`() {
        val plan = DecisionEngine.plan(analyze("Pause mining when temperature becomes high"))
        val step = plan.steps.first()
        assertEquals("configure_automation", step.tool)
        assertEquals("BATTERY_TEMP_OVER", step.args["condition"])
        assertTrue(step.requiresConfirmation)
    }

    @Test
    fun `help plan is read-only`() {
        val plan = DecisionEngine.plan(analyze("help"))
        assertEquals(1, plan.steps.size)
        assertEquals("help", plan.steps.first().tool)
    }
}
