package com.ac3codes.batchcommandrunner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandUtilsTest {

    // ---- normalization / comment handling ----------------------------------------------

    @Test
    void stripLeadingSlashRemovesOnlyOneSlash() {
        assertEquals("fill 0 0 0", CommandUtils.stripLeadingSlash("/fill 0 0 0"));
        assertEquals("///fill 0 0 0", CommandUtils.stripLeadingSlash("////fill 0 0 0"));
        assertEquals("say hi", CommandUtils.stripLeadingSlash("  say hi  "));
    }

    @Test
    void isBlankOrCommentDetectsBlankAndCommentLines() {
        assertTrue(CommandUtils.isBlankOrComment(""));
        assertTrue(CommandUtils.isBlankOrComment("   "));
        assertTrue(CommandUtils.isBlankOrComment("# a comment"));
        assertTrue(CommandUtils.isBlankOrComment("   # indented comment"));
        assertFalse(CommandUtils.isBlankOrComment("/say hello"));
        assertFalse(CommandUtils.isBlankOrComment("say hello"));
    }

    // ---- classification: direct heavy commands ------------------------------------------

    @Test
    void detectsFillInAllRequiredForms() {
        assertEquals(CommandType.FILL, CommandUtils.detectType("/fill 0 0 0 1 1 1 stone"));
        assertEquals(CommandType.FILL, CommandUtils.detectType("fill 0 0 0 1 1 1 stone"));
        assertEquals(CommandType.FILL, CommandUtils.detectType("/FILL 0 0 0 1 1 1 stone"));
    }

    @Test
    void detectsCloneCommand() {
        assertEquals(CommandType.CLONE, CommandUtils.detectType("/clone 0 0 0 10 10 10 20 20 20"));
    }

    @Test
    void detectsPlaceCommand() {
        assertEquals(CommandType.PLACE, CommandUtils.detectType("/place structure minecraft:village_plains"));
    }

    @Test
    void detectsSummonCommand() {
        assertEquals(CommandType.SUMMON, CommandUtils.detectType("/summon minecraft:zombie"));
    }

    @Test
    void detectsOrdinaryCommandsAsNormal() {
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/say hello"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/time set day"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/setblock 0 0 0 stone"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/kill @e"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/teleport @s 0 0 0"));
    }

    // ---- classification: execute ... run chains ------------------------------------------

    @Test
    void detectsFillNestedInExecute() {
        assertEquals(CommandType.FILL, CommandUtils.detectType("/execute positioned 0 0 0 run fill 0 0 0 10 10 10 stone"));
    }

    @Test
    void detectsCloneNestedInExecute() {
        assertEquals(CommandType.CLONE, CommandUtils.detectType("/execute as @e run clone 0 0 0 10 10 10 20 20 20"));
    }

    @Test
    void detectsSummonNestedInExecute() {
        assertEquals(CommandType.SUMMON, CommandUtils.detectType("/execute at @s run summon minecraft:zombie"));
    }

    @Test
    void detectsPlaceNestedInExecute() {
        assertEquals(CommandType.PLACE, CommandUtils.detectType("/execute positioned 0 0 0 run place structure minecraft:village_plains"));
    }

    @Test
    void detectsHeavyCommandNestedInDoublyNestedExecute() {
        assertEquals(CommandType.FILL, CommandUtils.detectType("/execute as @a run execute at @s run fill 0 0 0 10 10 10 stone"));
    }

    @Test
    void executeWithoutRunClauseStaysNormal() {
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/execute if block 0 0 0 stone"));
    }

    @Test
    void executeRunningNormalCommandStaysNormal() {
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/execute as @a run say hello"));
    }

    // ---- false positives -------------------------------------------------------------------

    @Test
    void rejectsLookalikesThatAreNotActualCommandTokens() {
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/function namespace:fill_platform"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/function example:summon_wave"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/say fill"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/say clone"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/tellraw @a {\"text\":\"place\"}"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("fillet 0 0 0"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType(""));
    }

    // ---- classify() / BatchEntry -------------------------------------------------------------

    @Test
    void classifyKeepsOriginalTextButClassifiesTheNestedCommand() {
        BatchEntry entry = CommandUtils.classify("/execute positioned 100 100 100 run fill 0 64 0 100 64 100 minecraft:stone");
        assertEquals("execute positioned 100 100 100 run fill 0 64 0 100 64 100 minecraft:stone", entry.command());
        assertEquals(CommandType.FILL, entry.type());
        assertTrue(entry.hasEstimatedWork());
    }

    @Test
    void classifyGivesPlaceAndSummonNoEstimatedWork() {
        assertFalse(CommandUtils.classify("/place structure minecraft:village_plains").hasEstimatedWork());
        assertFalse(CommandUtils.classify("/summon minecraft:zombie").hasEstimatedWork());
        assertEquals(-1L, CommandUtils.classify("/place structure minecraft:village_plains").estimatedWork());
        assertEquals(-1L, CommandUtils.classify("/summon minecraft:zombie").estimatedWork());
    }

    @Test
    void parseEntriesSkipsBlankAndCommentLinesAndStripsSlash() {
        String text = """
                /time set day

                # a comment
                fill 0 64 0 10 64 10 minecraft:stone
                /weather clear""";
        List<BatchEntry> entries = CommandUtils.parseEntries(text);
        assertEquals(3, entries.size());
        assertEquals("time set day", entries.get(0).command());
        assertEquals(CommandType.NORMAL, entries.get(0).type());
        assertEquals("fill 0 64 0 10 64 10 minecraft:stone", entries.get(1).command());
        assertEquals(CommandType.FILL, entries.get(1).type());
        assertEquals("weather clear", entries.get(2).command());
    }

    @Test
    void parseEntriesPreservesOrderAcrossMixedProtectedAndNormalCommands() {
        String text = "/say before\n/fill 0 0 0 1 1 1 stone\n/say middle\n/clone 0 0 0 1 1 1 5 5 5\n/say after";
        List<BatchEntry> entries = CommandUtils.parseEntries(text);
        assertEquals(5, entries.size());
        assertEquals("say before", entries.get(0).command());
        assertEquals("fill 0 0 0 1 1 1 stone", entries.get(1).command());
        assertEquals("say middle", entries.get(2).command());
        assertEquals("clone 0 0 0 1 1 1 5 5 5", entries.get(3).command());
        assertEquals("say after", entries.get(4).command());
    }

    @Test
    void countCommandsMatchesParseEntriesSizeWithoutBuildingAList() {
        String text = "/say a\n\n# comment\nfill 0 0 0 1 1 1 stone\n   \n/say b";
        assertEquals(CommandUtils.parseEntries(text).size(), CommandUtils.countCommands(text));
        assertEquals(3, CommandUtils.countCommands(text));
    }

    @Test
    void countCommandsHandlesEmptyAndNullInput() {
        assertEquals(0, CommandUtils.countCommands(""));
        assertEquals(0, CommandUtils.countCommands(null));
        assertEquals(0, CommandUtils.countCommands("\n\n\n"));
    }

    // ---- volume estimation: fill -------------------------------------------------------------

    @Test
    void estimateFillBlocksComputesVolumeForAbsoluteCoordinates() {
        assertEquals(1000L, CommandUtils.estimateFillBlocks("fill -500 1700 0 499 1700 0 minecraft:snow_block replace minecraft:air"));
        assertEquals(1000L, CommandUtils.estimateFillBlocks("fill 0 0 0 9 9 9"));
    }

    @Test
    void estimateFillBlocksHandlesSingleBlockFill() {
        assertEquals(1L, CommandUtils.estimateFillBlocks("fill 5 5 5 5 5 5 minecraft:stone"));
    }

    @Test
    void estimateFillBlocksHandlesReversedAndNegativeCoordinates() {
        assertEquals(1000L, CommandUtils.estimateFillBlocks("fill 9 9 9 0 0 0"));
        assertEquals(1000L, CommandUtils.estimateFillBlocks("fill -5 -5 -5 4 4 4"));
    }

    @Test
    void estimateFillBlocksReturnsUnknownForRelativeCoordinates() {
        assertEquals(-1L, CommandUtils.estimateFillBlocks("fill ~ ~ ~ ~10 ~10 ~10 minecraft:stone"));
    }

    @Test
    void estimateFillBlocksReturnsUnknownForLocalCoordinates() {
        assertEquals(-1L, CommandUtils.estimateFillBlocks("fill ^ ^ ^ ^5 ^5 ^5 minecraft:stone"));
    }

    @Test
    void estimateFillBlocksReturnsUnknownForMalformedInput() {
        assertEquals(-1L, CommandUtils.estimateFillBlocks("fill 0 0 0"));
        assertEquals(-1L, CommandUtils.estimateFillBlocks("fill"));
        assertEquals(-1L, CommandUtils.estimateFillBlocks("fill a b c d e f minecraft:stone"));
    }

    @Test
    void estimateFillBlocksReturnsUnknownForNonFillCommands() {
        assertEquals(-1L, CommandUtils.estimateFillBlocks("say hello"));
        assertEquals(-1L, CommandUtils.estimateFillBlocks("clone 0 0 0 9 9 9 100 100 100"));
    }

    @Test
    void estimateFillBlocksSaturatesInsteadOfOverflowingForHugeSpans() {
        long huge = CommandUtils.estimateFillBlocks("fill -30000000 -2032 -30000000 30000000 2032 30000000");
        assertTrue(huge > 0, "volume estimate must never go negative from overflow");
        assertEquals(Long.MAX_VALUE, huge);
    }

    // ---- volume estimation: clone -------------------------------------------------------------

    @Test
    void estimateCloneBlocksComputesSourceVolumeIgnoringDestination() {
        assertEquals(1000L, CommandUtils.estimateCloneBlocks("clone 0 0 0 9 9 9 100 100 100"));
    }

    @Test
    void estimateCloneBlocksHandlesReversedAndNegativeCoordinates() {
        assertEquals(1000L, CommandUtils.estimateCloneBlocks("clone 9 9 9 0 0 0 500 500 500"));
        assertEquals(1000L, CommandUtils.estimateCloneBlocks("clone -5 -5 -5 4 4 4 0 0 0"));
    }

    @Test
    void estimateCloneBlocksReturnsUnknownForRelativeOrLocalCoordinates() {
        assertEquals(-1L, CommandUtils.estimateCloneBlocks("clone ~ ~ ~ ~10 ~10 ~10 0 0 0"));
        assertEquals(-1L, CommandUtils.estimateCloneBlocks("clone ^ ^ ^ ^5 ^5 ^5 0 0 0"));
    }

    @Test
    void estimateCloneBlocksReturnsUnknownForMalformedInput() {
        assertEquals(-1L, CommandUtils.estimateCloneBlocks("clone 0 0 0"));
        assertEquals(-1L, CommandUtils.estimateCloneBlocks("clone"));
        assertEquals(-1L, CommandUtils.estimateCloneBlocks("clone a b c d e f 0 0 0"));
    }

    @Test
    void estimateCloneBlocksSaturatesInsteadOfOverflowingForHugeSpans() {
        long huge = CommandUtils.estimateCloneBlocks("clone -30000000 -2032 -30000000 30000000 2032 30000000 0 0 0");
        assertTrue(huge > 0, "volume estimate must never go negative from overflow");
        assertEquals(Long.MAX_VALUE, huge);
    }

    // ---- effective delay ---------------------------------------------------------------------

    @Test
    void normalCommandAlwaysUsesNormalDelay() {
        BatchEntry entry = new BatchEntry("say hello", CommandType.NORMAL, -1);
        assertEquals(0, CommandUtils.calculateEffectiveDelay(entry, new BatchSettings(0, true, 10, 10, 20, 2)));
        assertEquals(50, CommandUtils.calculateEffectiveDelay(entry, new BatchSettings(50, true, 10, 10, 20, 2)));
    }

    @Test
    void fillUsesFillMinimumWhenLargerThanNormalDelay() {
        BatchEntry entry = new BatchEntry("fill 0 0 0 1 1 1 stone", CommandType.FILL, 8);
        int delay = CommandUtils.calculateEffectiveDelay(entry, new BatchSettings(1, true, 10, 10, 20, 2));
        assertTrue(delay >= 10);
    }

    @Test
    void cloneUsesCloneMinimumWhenLargerThanNormalDelay() {
        BatchEntry entry = new BatchEntry("clone 0 0 0 1 1 1 5 5 5", CommandType.CLONE, 8);
        int delay = CommandUtils.calculateEffectiveDelay(entry, new BatchSettings(1, true, 10, 10, 20, 2));
        assertTrue(delay >= 10);
    }

    @Test
    void placeUsesPlaceMinimumWhenLargerThanNormalDelay() {
        BatchEntry entry = new BatchEntry("place structure minecraft:village_plains", CommandType.PLACE, -1);
        int delay = CommandUtils.calculateEffectiveDelay(entry, new BatchSettings(1, true, 10, 10, 20, 2));
        assertTrue(delay >= 20);
    }

    @Test
    void summonUsesSummonMinimumWhenLargerThanNormalDelay() {
        BatchEntry entry = new BatchEntry("summon minecraft:zombie", CommandType.SUMMON, -1);
        int delay = CommandUtils.calculateEffectiveDelay(entry, new BatchSettings(1, true, 10, 10, 20, 2));
        assertTrue(delay >= 2);
    }

    @Test
    void normalDelayWinsWhenLargerThanFillMinimum() {
        BatchEntry entry = new BatchEntry("fill 0 0 0 1 1 1 stone", CommandType.FILL, 8);
        assertEquals(50, CommandUtils.calculateEffectiveDelay(entry, new BatchSettings(50, true, 10, 10, 20, 2)));
    }

    @Test
    void protectionOffMeansEveryTypeJustUsesNormalDelay() {
        BatchEntry fill = new BatchEntry("fill 0 0 0 100 100 100 stone", CommandType.FILL, 1_030_301);
        BatchEntry place = new BatchEntry("place structure minecraft:village_plains", CommandType.PLACE, -1);
        BatchSettings settings = new BatchSettings(2, false, 10, 10, 20, 2);
        assertEquals(2, CommandUtils.calculateEffectiveDelay(fill, settings));
        assertEquals(2, CommandUtils.calculateEffectiveDelay(place, settings));
    }

    @Test
    void largeFillTriggersAdaptiveMinimum() {
        BatchSettings settings = new BatchSettings(1, true, 5, 5, 20, 2);

        BatchEntry small = new BatchEntry("fill 0 0 0 9 9 9 stone", CommandType.FILL, 1_000);
        assertEquals(5, CommandUtils.calculateEffectiveDelay(small, settings));

        BatchEntry medium = new BatchEntry("fill 0 0 0 20 20 20 stone", CommandType.FILL, 9_261);
        assertEquals(10, CommandUtils.calculateEffectiveDelay(medium, settings));

        BatchEntry large = new BatchEntry("fill 0 0 0 32 32 32 stone", CommandType.FILL, 32_768);
        assertEquals(20, CommandUtils.calculateEffectiveDelay(large, settings));

        BatchEntry huge = new BatchEntry("fill 0 0 0 50 50 50 stone", CommandType.FILL, 132_651);
        assertEquals(40, CommandUtils.calculateEffectiveDelay(huge, settings));
    }

    @Test
    void largeCloneTriggersAdaptiveMinimum() {
        BatchSettings settings = new BatchSettings(1, true, 5, 5, 20, 2);
        BatchEntry hugeClone = new BatchEntry("clone 0 0 0 50 50 50 0 0 0", CommandType.CLONE, 132_651);
        assertEquals(40, CommandUtils.calculateEffectiveDelay(hugeClone, settings));
    }

    @Test
    void unknownEstimateFallsBackToConfiguredMinimum() {
        BatchEntry fillUnknown = new BatchEntry("fill ~ ~ ~ ~10 ~10 ~10 stone", CommandType.FILL, -1);
        BatchEntry cloneUnknown = new BatchEntry("clone ~ ~ ~ ~10 ~10 ~10 0 0 0", CommandType.CLONE, -1);
        BatchSettings settings = new BatchSettings(1, true, 7, 9, 20, 2);
        assertEquals(7, CommandUtils.calculateEffectiveDelay(fillUnknown, settings));
        assertEquals(9, CommandUtils.calculateEffectiveDelay(cloneUnknown, settings));
    }

    // ---- adaptive threshold boundaries (strict >, per tier) --------------------------------

    @Test
    void adaptiveThresholdBoundariesAreStrictlyGreaterThan() {
        // Configured minimums are 0 so only the adaptive tier is visible in the result.
        BatchSettings settings = new BatchSettings(0, true, 0, 0, 20, 2);
        assertEquals(0, delayForFillVolume(999, settings), "999 stays in the <=1,000 tier");
        assertEquals(0, delayForFillVolume(1_000, settings), "exactly 1,000 stays in the <=1,000 tier");
        assertEquals(10, delayForFillVolume(1_001, settings), "1,001 crosses into the >1,000 tier");
        assertEquals(10, delayForFillVolume(9_999, settings));
        assertEquals(10, delayForFillVolume(10_000, settings), "exactly 10,000 stays in the <=10,000 tier");
        assertEquals(20, delayForFillVolume(10_001, settings), "10,001 crosses into the >10,000 tier");
        assertEquals(20, delayForFillVolume(32_767, settings));
        assertEquals(20, delayForFillVolume(32_768, settings), "exactly 32,768 stays in the <=32,768 tier");
        assertEquals(40, delayForFillVolume(32_769, settings), "32,769 crosses into the >32,768 tier");
    }

    @Test
    void cloneUsesTheSameAdaptiveBoundaryAsFill() {
        BatchSettings settings = new BatchSettings(0, true, 0, 0, 20, 2);
        BatchEntry atBoundary = new BatchEntry("clone 0 0 0 1 1 1 0 0 0", CommandType.CLONE, 10_000);
        BatchEntry overBoundary = new BatchEntry("clone 0 0 0 1 1 1 0 0 0", CommandType.CLONE, 10_001);
        assertEquals(10, CommandUtils.calculateEffectiveDelay(atBoundary, settings));
        assertEquals(20, CommandUtils.calculateEffectiveDelay(overBoundary, settings));
    }

    private static int delayForFillVolume(long blocks, BatchSettings settings) {
        BatchEntry entry = new BatchEntry("fill 0 0 0 1 1 1 stone", CommandType.FILL, blocks);
        return CommandUtils.calculateEffectiveDelay(entry, settings);
    }

    // ---- explicit worked examples from the spec ---------------------------------------------

    @Test
    void configuredMinimumWinsOverAdaptiveMinimumWhenLarger() {
        // configured fill minimum = 30, estimated blocks = 5,000 -> 30 ticks, not the adaptive 10.
        BatchEntry entry = new BatchEntry("fill 0 0 0 16 16 16 stone", CommandType.FILL, 5_000);
        BatchSettings settings = new BatchSettings(0, true, 30, 10, 20, 2);
        assertEquals(30, CommandUtils.calculateEffectiveDelay(entry, settings));
    }

    @Test
    void normalDelayWinsOverProtectionMinimumWorkedExample() {
        // normal delay = 50, protection minimum = 40 -> 50 ticks.
        BatchEntry entry = new BatchEntry("fill 0 0 0 1 1 1 stone", CommandType.FILL, -1);
        BatchSettings settings = new BatchSettings(50, true, 40, 40, 40, 40);
        assertEquals(50, CommandUtils.calculateEffectiveDelay(entry, settings));
    }

    @Test
    void normalDelayExceedingEveryTypesMinimumMeansNoActualThrottling() {
        // CommandBatchRunner.isCurrentCommandProtected() reports "protected" based on
        // effectiveDelay > normalDelay. If any of these ever came back above 50, the UI would
        // wrongly claim protection added time when nothing was actually held back.
        BatchSettings settings = new BatchSettings(50, true, 10, 10, 20, 2);
        BatchEntry fill = new BatchEntry("fill 0 0 0 1 1 1 stone", CommandType.FILL, 5);
        BatchEntry clone = new BatchEntry("clone 0 0 0 1 1 1 5 5 5", CommandType.CLONE, 5);
        BatchEntry place = new BatchEntry("place structure minecraft:village_plains", CommandType.PLACE, -1);
        BatchEntry summon = new BatchEntry("summon minecraft:zombie", CommandType.SUMMON, -1);
        assertEquals(50, CommandUtils.calculateEffectiveDelay(fill, settings));
        assertEquals(50, CommandUtils.calculateEffectiveDelay(clone, settings));
        assertEquals(50, CommandUtils.calculateEffectiveDelay(place, settings));
        assertEquals(50, CommandUtils.calculateEffectiveDelay(summon, settings));
    }

    // ---- command preservation for every protected type nested in execute --------------------

    @Test
    void classifyPreservesOriginalTextForEveryProtectedTypeNestedInExecute() {
        assertEquals("execute as @e run clone 0 0 0 10 10 10 20 20 20",
                CommandUtils.classify("/execute as @e run clone 0 0 0 10 10 10 20 20 20").command());
        assertEquals("execute positioned 0 0 0 run place structure minecraft:village_plains",
                CommandUtils.classify("/execute positioned 0 0 0 run place structure minecraft:village_plains").command());
        assertEquals("execute at @s run summon minecraft:zombie",
                CommandUtils.classify("/execute at @s run summon minecraft:zombie").command());
    }

    // ---- additional false positives -----------------------------------------------------------

    @Test
    void additionalFalsePositivesStayNormal() {
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/say place"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/say summon"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/tellraw @a {\"text\":\"fill\"}"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/tellraw @a {\"text\":\"run fill\"}"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/execute run"), "execute with a dangling run and no actual command stays NORMAL, not a crash");
    }

    @Test
    void exactTokenMatchRejectsPrefixCollisionsForEveryProtectedType() {
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/placement 0 0 0"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/summoning minecraft:zombie"));
        assertEquals(CommandType.NORMAL, CommandUtils.detectType("/cloner 0 0 0"));
    }

    @Test
    void detectionIsCaseInsensitiveForEveryProtectedType() {
        assertEquals(CommandType.CLONE, CommandUtils.detectType("/CLONE 0 0 0 1 1 1 5 5 5"));
        assertEquals(CommandType.PLACE, CommandUtils.detectType("/Place structure minecraft:village_plains"));
        assertEquals(CommandType.SUMMON, CommandUtils.detectType("/SuMmOn minecraft:zombie"));
    }

    // ---- execute-chain scanner: quoted "run" false-positive regression ----------------------

    @Test
    void executeIgnoresRunKeywordHiddenInsideQuotedSelectorNbt() {
        // Before the quote-aware scan, the "run" inside this quoted NBT string was read as a
        // standalone token and misidentified as the execute ... run separator, so the whole
        // command was wrongly classified as NORMAL instead of FILL. Confirmed by manual trace
        // of the pre-fix findWholeToken() against this exact string.
        String command = "/execute as @e[nbt={CustomTag:\"please run now\"}] at @s run fill 0 0 0 5 5 5 stone";
        assertEquals(CommandType.FILL, CommandUtils.detectType(command));
    }

    @Test
    void executeIgnoresRunKeywordInsideSingleQuotedNestedJsonText() {
        String command = "/execute as @e[nbt={CustomTag:'\"the sheep will run away\"'}] at @s run summon minecraft:sheep";
        assertEquals(CommandType.SUMMON, CommandUtils.detectType(command));
    }

    @Test
    void executeStillDetectsRealRunAfterUnrelatedQuotedArgument() {
        String command = "/execute if data entity @s Item{tag:{display:{Name:'\"Fancy Sword\"'}}} run clone 0 0 0 1 1 1 5 5 5";
        assertEquals(CommandType.CLONE, CommandUtils.detectType(command));
    }

    // ---- volume estimation: Math.abs(Long.MIN_VALUE) overflow regression --------------------

    @Test
    void estimateFillBlocksNeverGoesNegativeAtTheLongMinValueBoundary() {
        // Math.abs(Long.MIN_VALUE) famously still returns a negative number. This coordinate
        // pairing makes the raw (x2 - x1) difference land exactly on Long.MIN_VALUE, which used
        // to produce a negative "volume" before safeSpan() guarded against it.
        long blocks = CommandUtils.estimateFillBlocks("fill 1 0 0 -9223372036854775807 0 0");
        assertTrue(blocks >= 0, "volume estimate must never be negative");
        assertEquals(Long.MAX_VALUE, blocks);
    }

    @Test
    void estimateCloneBlocksNeverGoesNegativeAtTheLongMinValueBoundary() {
        long blocks = CommandUtils.estimateCloneBlocks("clone 1 0 0 -9223372036854775807 0 0 0 0 0");
        assertTrue(blocks >= 0, "volume estimate must never be negative");
        assertEquals(Long.MAX_VALUE, blocks);
    }

    // ---- mixed-batch scenario: order, classification, and delay tiers together --------------

    @Test
    void mixedBatchScenarioPreservesOrderAndAppliesExpectedDelayTiers() {
        String text = """
                /say normal-1
                /fill 0 100 0 20 100 20 minecraft:stone
                /say normal-2
                /clone 0 100 0 20 100 20 50 100 50
                /summon minecraft:zombie
                /place structure minecraft:village_plains
                /execute positioned 100 100 100 run fill 0 100 0 50 100 50 minecraft:snow_block
                /say finished""";
        BatchSettings settings = new BatchSettings(0, true, 10, 10, 20, 2);

        List<BatchEntry> entries = CommandUtils.parseEntries(text);
        assertEquals(8, entries.size());

        assertEquals("say normal-1", entries.get(0).command());
        assertEquals("fill 0 100 0 20 100 20 minecraft:stone", entries.get(1).command());
        assertEquals("say normal-2", entries.get(2).command());
        assertEquals("clone 0 100 0 20 100 20 50 100 50", entries.get(3).command());
        assertEquals("summon minecraft:zombie", entries.get(4).command());
        assertEquals("place structure minecraft:village_plains", entries.get(5).command());
        assertEquals("execute positioned 100 100 100 run fill 0 100 0 50 100 50 minecraft:snow_block", entries.get(6).command());
        assertEquals("say finished", entries.get(7).command());

        assertEquals(CommandType.NORMAL, entries.get(0).type());
        assertEquals(CommandType.FILL, entries.get(1).type());
        assertEquals(CommandType.NORMAL, entries.get(2).type());
        assertEquals(CommandType.CLONE, entries.get(3).type());
        assertEquals(CommandType.SUMMON, entries.get(4).type());
        assertEquals(CommandType.PLACE, entries.get(5).type());
        assertEquals(CommandType.FILL, entries.get(6).type());
        assertEquals(CommandType.NORMAL, entries.get(7).type());

        assertEquals(0, CommandUtils.calculateEffectiveDelay(entries.get(0), settings));
        assertTrue(CommandUtils.calculateEffectiveDelay(entries.get(1), settings) >= 10);
        assertEquals(0, CommandUtils.calculateEffectiveDelay(entries.get(2), settings));
        assertTrue(CommandUtils.calculateEffectiveDelay(entries.get(3), settings) >= 10);
        assertTrue(CommandUtils.calculateEffectiveDelay(entries.get(4), settings) >= 2);
        assertTrue(CommandUtils.calculateEffectiveDelay(entries.get(5), settings) >= 20);
        assertTrue(CommandUtils.calculateEffectiveDelay(entries.get(6), settings) >= 10);
        assertEquals(0, CommandUtils.calculateEffectiveDelay(entries.get(7), settings));
    }
}
