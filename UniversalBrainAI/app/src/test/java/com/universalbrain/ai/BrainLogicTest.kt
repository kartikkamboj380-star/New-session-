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
    @Test
    fun `number words and digits are parsed`() {
        assertEquals(3, CommandParser.parse("create three clones").number)
        assertEquals(2, CommandParser.parse("open 2 instances").number)
        assertEquals(2, CommandParser.parse("open two instances").number)
    }
    @Test
    fun `urls wallets and thresholds are extracted`() {
        val parsed = CommandParser.parse("add worker url https://api.ethermine.org/miner/X wallet 0xabc def coin eth over 45")
        // Parser is case-insensitive, so the URL comes back lowercased.
        assertEquals("https://api.ethermine.org/miner/x", parsed.url)
        assertEquals("0xabc", parsed.wallet)
        assertEquals(45.0, parsed.threshold!!, 0.001)
    }
    @Test
    fun `fresh session and on off flags are detected`() {
        assertTrue(CommandParser.parse("open a fresh sign in session").mentionsFresh)
        assertTrue(CommandParser.parse("battery saver on").onOff == true)
        assertTrue(CommandParser.parse("battery saver off").onOff == false)
    }
    @Test
    fun `open app intent with target`() {
        val analysis = analyze("open ctpool")
        assertEquals(IntentAnalyzer.Intent.OPEN_APP, analysis.intent)
        assertEquals("ctpool", IntentAnalyzer.targetAfterVerb(CommandParser.parse("open ctpool").words))
        assertEquals(IntentAnalyzer.Intent.OPEN_INSTANCES, analyze("open 2 instances").intent)
    }
    @Test
    fun `mining pause vs resume vs temp automation`() {
        assertEquals(IntentAnalyzer.Intent.MINING_PAUSE, analyze("pause mining now").intent)
        assertEquals(IntentAnalyzer.Intent.MINING_RESUME, analyze("resume mining").intent)
        assertEquals(IntentAnalyzer.Intent.PAUSE_MINING_ON_TEMP, analyze("pause mining when temperature is over 45").intent)
        val plan = DecisionEngine.plan(analyze("pause mining when temperature is over 45"), CommandParser.parse("pause mining when temperature is over 45"))
        assertEquals("45.0", plan.steps.first().args["value"])
    }
    @Test
    fun `ads and battery saver intents`() {
        assertEquals(IntentAnalyzer.Intent.ADS_WATCH, analyze("watch ads").intent)
        assertEquals(IntentAnalyzer.Intent.ADS_STATUS, analyze("ads status").intent)
        assertEquals(IntentAnalyzer.Intent.BATTERY_SAVER, analyze("battery saver on").intent)
    }
    @Test
    fun `recommend intent`() {
        assertEquals(IntentAnalyzer.Intent.RECOMMEND, analyze("what should i do to improve").intent)
    }
    @Test
    fun `automation create intent from notify command`() {
        assertEquals(IntentAnalyzer.Intent.AUTOMATION_CREATE, analyze("notify me when network is lost").intent)
        val plan = DecisionEngine.plan(
            analyze("notify me when network is lost"),
            CommandParser.parse("notify me when network is lost")
        )
        assertEquals("NETWORK_LOST", plan.steps.first().args["condition"])
        assertEquals("NOTIFY", plan.steps.first().args["action"])
    }
    @Test
    fun `destructive new plans require confirmation`() {
        val wipePlan = DecisionEngine.plan(analyze("wipe all secrets"))
        assertTrue(wipePlan.steps.any { it.requiresConfirmation })
        val deletePlan = DecisionEngine.plan(analyze("delete my ctpool clone"))
        assertEquals("delete_instance", deletePlan.steps.first().tool)
        assertTrue(deletePlan.steps.first().requiresConfirmation)
    }
    @Test
    fun `unknown commands get nearest intent suggestions`() {
        val analysis = analyze("mining worker offline check")
        assertEquals(IntentAnalyzer.Intent.UNKNOWN, analysis.intent)
        assertTrue(analysis.suggestion != null)
        assertEquals(null, analyze("tell me a joke about penguins").suggestion)
    }
    @Test
    fun `web clone plan carries url and fresh flag`() {
        val parsed = CommandParser.parse("create web clone of ctpool url https://ctpool.com")
        val plan = DecisionEngine.plan(analyze("create web clone of ctpool url https://ctpool.com"), parsed)
        val step = plan.steps.first()
        assertEquals("create_instance", step.tool)
        assertEquals("WEB", step.args["kind"])
        assertEquals("https://ctpool.com", step.args["url"])
        assertTrue(step.args["fresh"] == "true")
    }
}
