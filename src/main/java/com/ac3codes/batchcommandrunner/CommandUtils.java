package com.ac3codes.batchcommandrunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Central place for command-line normalization, heavy-command classification (including
 * {@code execute ... run <command>} chains), size estimation, and batch-wide parsing/counting.
 * Kept dependency-free (no Minecraft classes) so it can be unit tested directly and reused from
 * both the runner and the UI.
 *
 * <p>This is a rate limiter, not a sandbox: classification only ever looks at the command's own
 * leading tokens. It never inspects functions/datapacks (a command like
 * {@code /function ns:massive_world_edit} always stays NORMAL - there's no way to safely know
 * what it will do without executing it), and it never tries to predict NBT or entity-AI cost.
 */
public final class CommandUtils {

    /** Below or at this many blocks, Heavy Command Protection doesn't raise the delay above the configured minimum. */
    private static final long ADAPTIVE_SMALL_THRESHOLD = 1_000;
    /** Above this, at least 10 ticks are enforced. */
    private static final long ADAPTIVE_MEDIUM_THRESHOLD = 10_000;
    /** Above this, at least 20 ticks are enforced. */
    private static final long ADAPTIVE_LARGE_THRESHOLD = 32_768;

    private static final int ADAPTIVE_MEDIUM_MIN_DELAY = 10;
    private static final int ADAPTIVE_LARGE_MIN_DELAY = 20;
    private static final int ADAPTIVE_HUGE_MIN_DELAY = 40;

    /** Bounds how many "execute ... run" layers get unwrapped, purely so pathological/adversarial
     * input can't loop - in practice a handful of nested executes is already an extreme case. */
    private static final int MAX_EXECUTE_UNWRAP_DEPTH = 32;

    private CommandUtils() {
    }

    /**
     * Strips a single leading slash (if present) and surrounding whitespace from one line.
     * Only one slash is ever removed - a normal command needs no more than that, and looping
     * would just be extra allocation for unusual input like {@code ////fill}.
     */
    public static String stripLeadingSlash(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).stripLeading();
        }
        return trimmed;
    }

    /**
     * True for blank lines and lines whose first non-whitespace character is {@code #}
     * (the batch editor's comment syntax). Both are skipped rather than executed.
     */
    public static boolean isBlankOrComment(CharSequence line) {
        int start = 0;
        int end = line.length();
        while (start < end && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        if (start >= end) {
            return true;
        }
        return line.charAt(start) == '#';
    }

    /**
     * Classifies a command line (with or without a leading slash) into its {@link CommandType},
     * unwrapping any {@code execute ... run} chain first so a heavy command hidden behind
     * {@code /execute} (including nested chains) is still detected. Only ever looks at command
     * tokens, so lookalikes like {@code /function ns:fill_platform} or {@code /say fill} are
     * never misclassified.
     */
    public static CommandType detectType(String line) {
        return classifyEffective(stripLeadingSlash(line)).type();
    }

    /**
     * Classifies one already-normalized (comment/blank lines excluded) command line into a
     * {@link BatchEntry}, computing its size estimate (if any) up front so the runner never has
     * to re-parse or re-classify it. {@link BatchEntry#command()} always keeps the original,
     * un-unwrapped text (including any {@code execute ...} wrapper) since that's what actually
     * needs to be sent to the server - only classification looks past the wrapper.
     */
    public static BatchEntry classify(String line) {
        String command = stripLeadingSlash(line);
        Classification classification = classifyEffective(command);
        return new BatchEntry(command, classification.type(), classification.estimatedWork());
    }

    private record Classification(CommandType type, long estimatedWork) {
    }

    private static Classification classifyEffective(String normalizedCommand) {
        String effective = unwrapExecuteChain(normalizedCommand);
        CommandType type = detectLeafType(effective);
        long estimatedWork = switch (type) {
            case FILL -> estimateFillBlocks(effective);
            case CLONE -> estimateCloneBlocks(effective);
            case PLACE, SUMMON, NORMAL -> -1;
        };
        return new Classification(type, estimatedWork);
    }

    private static CommandType detectLeafType(String command) {
        if (firstTokenEquals(command, "fill")) {
            return CommandType.FILL;
        } else if (firstTokenEquals(command, "clone")) {
            return CommandType.CLONE;
        } else if (firstTokenEquals(command, "place")) {
            return CommandType.PLACE;
        } else if (firstTokenEquals(command, "summon")) {
            return CommandType.SUMMON;
        } else {
            return CommandType.NORMAL;
        }
    }

    /**
     * Unwraps {@code execute ... run <command>} chains (including nested ones, e.g.
     * {@code execute as @a run execute at @s run fill ...}) to find the command actually being
     * invoked. This is lightweight token scanning, not a Brigadier parser - it just walks
     * whitespace-delimited tokens looking for a bare {@code run} keyword, which is enough for
     * classification without validating the rest of the execute clause. Returns the input
     * unchanged if it isn't an execute chain, or if no {@code run} token can be found in it.
     */
    private static String unwrapExecuteChain(String command) {
        String remaining = command;
        for (int depth = 0; depth < MAX_EXECUTE_UNWRAP_DEPTH; depth++) {
            if (!firstTokenEquals(remaining, "execute")) {
                return remaining;
            }
            int runStart = findWholeToken(remaining, "run", "execute".length());
            if (runStart < 0) {
                return remaining;
            }
            String tail = remaining.substring(runStart + "run".length()).stripLeading();
            if (tail.isEmpty()) {
                return remaining;
            }
            remaining = tail;
        }
        return remaining;
    }

    private static boolean firstTokenEquals(String command, String name) {
        String token = firstToken(command);
        return token.length() == name.length() && token.equalsIgnoreCase(name);
    }

    private static String firstToken(String command) {
        int end = 0;
        int len = command.length();
        while (end < len && !Character.isWhitespace(command.charAt(end))) {
            end++;
        }
        return command.substring(0, end);
    }

    /**
     * Finds the start index of a whitespace-delimited token exactly matching {@code token}
     * (case-insensitive), searching from {@code fromIndex} onward. Returns -1 if not found.
     * Deliberately just scans words rather than allocating a token array - this only needs to
     * answer "is there a bare 'run' token here", not tokenize the whole command.
     *
     * <p>Quoted spans (single or double quotes, honoring backslash escapes) are treated as part
     * of whatever token they're in rather than as separate words - without this, a selector or
     * NBT argument like {@code @e[nbt={CustomTag:"please run now"}]} would have "run" read out
     * as a lone word and misidentified as the execute ... run separator, even though it's just
     * text inside a quoted SNBT/JSON string. This is deliberately narrow (quote-span skipping
     * only, no bracket-depth tracking or full SNBT grammar) - see {@link #unwrapExecuteChain}.
     */
    private static int findWholeToken(String command, String token, int fromIndex) {
        int i = fromIndex;
        int len = command.length();
        while (i < len) {
            while (i < len && Character.isWhitespace(command.charAt(i))) {
                i++;
            }
            if (i >= len) {
                break;
            }
            int start = i;
            i = skipToken(command, i);
            if (i - start == token.length() && command.regionMatches(true, start, token, 0, token.length())) {
                return start;
            }
        }
        return -1;
    }

    /** Advances past one whitespace-delimited token starting at {@code start}, treating any
     * quoted span within it as opaque (see {@link #findWholeToken}). Returns the index just
     * past the token. */
    private static int skipToken(String command, int start) {
        int i = start;
        int len = command.length();
        while (i < len && !Character.isWhitespace(command.charAt(i))) {
            char c = command.charAt(i);
            i = (c == '"' || c == '\'') ? skipQuoted(command, i, c) : i + 1;
        }
        return i;
    }

    /** Skips from an opening quote at {@code start} to just past its matching closing quote,
     * honoring backslash escapes. If the quote is never closed, consumes to the end of the
     * string rather than looping - malformed input should never hang the scanner. */
    private static int skipQuoted(String command, int start, char quoteChar) {
        int i = start + 1;
        int len = command.length();
        while (i < len) {
            char c = command.charAt(i);
            if (c == '\\' && i + 1 < len) {
                i += 2;
            } else if (c == quoteChar) {
                return i + 1;
            } else {
                i++;
            }
        }
        return len;
    }

    /**
     * Best-effort block count for a simple absolute-coordinate /fill command
     * ({@code fill x1 y1 z1 x2 y2 z2 ...}). Returns {@code -1} rather than throwing when the
     * command doesn't match that simple shape - relative ({@code ~}) or local ({@code ^})
     * coordinates, selectors, or any other parse failure just means "unknown size", and callers
     * should fall back to the configured minimum delay instead of guessing.
     */
    public static long estimateFillBlocks(String command) {
        return estimateRegionVolume(command, "fill");
    }

    /**
     * Best-effort source-region block count for a simple absolute-coordinate /clone command
     * ({@code clone x1 y1 z1 x2 y2 z2 x y z ...}) - only the six source coordinates matter for
     * the size estimate, the destination doesn't change how many blocks are read/written.
     * Same fallback rules as {@link #estimateFillBlocks}.
     */
    public static long estimateCloneBlocks(String command) {
        return estimateRegionVolume(command, "clone");
    }

    private static long estimateRegionVolume(String command, String commandName) {
        if (!firstTokenEquals(command, commandName)) {
            return -1;
        }
        String rest = command.strip().substring(commandName.length()).stripLeading();
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        if (parts.length < 6) {
            return -1;
        }

        long[] coords = new long[6];
        for (int i = 0; i < 6; i++) {
            String part = parts[i];
            if (part.indexOf('~') >= 0 || part.indexOf('^') >= 0) {
                return -1;
            }
            try {
                coords[i] = Long.parseLong(part);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        long dx = safeSpan(coords[3], coords[0]);
        long dy = safeSpan(coords[4], coords[1]);
        long dz = safeSpan(coords[5], coords[2]);
        return safeVolume(dx, dy, dz);
    }

    /** Inclusive block count along one axis: {@code abs(a - b) + 1}, saturating to
     * {@code Long.MAX_VALUE} instead of overflowing. This also guards the classic
     * {@code Math.abs(Long.MIN_VALUE)} pitfall - that one input is the sole case where
     * {@code Math.abs} still returns a negative value - which would otherwise let a
     * pathological coordinate pair (reachable since {@link Long#parseLong} accepts the full
     * long range, far past any real Minecraft world border) produce a negative "block count". */
    private static long safeSpan(long a, long b) {
        long diff;
        try {
            diff = Math.subtractExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        long magnitude = diff == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(diff);
        return magnitude == Long.MAX_VALUE ? magnitude : magnitude + 1;
    }

    /** Multiplies the three dimensions, saturating to {@code Long.MAX_VALUE} instead of
     * overflowing/going negative for absurdly large (but technically valid) coordinate spans -
     * that's still unambiguously "huge" for adaptive-delay purposes, just not an exact count. */
    private static long safeVolume(long dx, long dy, long dz) {
        try {
            return Math.multiplyExact(Math.multiplyExact(dx, dy), dz);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Parses full batch editor text into classified entries, skipping blank/comment lines.
     * This does real per-line work (allocation, classification) and is meant to be called at
     * batch-start time, not on every keystroke or every tick - see {@link #countCommands} for
     * the cheap version used for a live "Commands: N" counter.
     */
    public static List<BatchEntry> parseEntries(String text) {
        List<BatchEntry> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (String rawLine : text.split("\\R", -1)) {
            if (isBlankOrComment(rawLine)) {
                continue;
            }
            BatchEntry entry = classify(rawLine);
            if (!entry.command().isEmpty()) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Counts executable (non-blank, non-comment) lines without allocating a parsed command
     * list or per-line substrings - just index scanning. Safe to call on every text-change
     * event even for a multi-thousand-line batch.
     */
    public static int countCommands(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        int length = text.length();
        int lineStart = 0;
        while (lineStart <= length) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? length : newline;
            if (!isLineBlankOrComment(text, lineStart, lineEnd)) {
                count++;
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        return count;
    }

    private static boolean isLineBlankOrComment(String text, int start, int end) {
        int i = start;
        // Line endings may be "\r\n"; trailing '\r' should not count as content.
        while (end > i && text.charAt(end - 1) == '\r') {
            end--;
        }
        while (i < end && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= end) {
            return true;
        }
        return text.charAt(i) == '#';
    }

    /**
     * The delay to wait after executing this entry, given the active settings. NORMAL commands
     * (and everything, when Heavy Command Protection is off) always use
     * {@link BatchSettings#normalDelay()}. Otherwise the larger of the normal delay and the
     * entry's per-type minimum is used, with FILL/CLONE additionally layering a size-adaptive
     * minimum on top when a reliable estimate exists.
     */
    public static int calculateEffectiveDelay(BatchEntry entry, BatchSettings settings) {
        if (!settings.heavyCommandProtection() || entry.type() == CommandType.NORMAL) {
            return settings.normalDelay();
        }

        int minimum = settings.minimumFor(entry.type());
        if (isSizeAdaptive(entry.type()) && entry.hasEstimatedWork()) {
            minimum = Math.max(minimum, adaptiveMinimumForVolume(entry.estimatedWork()));
        }
        return Math.max(settings.normalDelay(), minimum);
    }

    private static boolean isSizeAdaptive(CommandType type) {
        return type == CommandType.FILL || type == CommandType.CLONE;
    }

    /**
     * Optional adaptive minimum, layered on top of the configured minimum for larger fills/
     * clones. Callers only reach this when a reliable size estimate exists; unknown/unparseable
     * regions simply use the configured minimum untouched.
     */
    private static int adaptiveMinimumForVolume(long blocks) {
        if (blocks <= ADAPTIVE_SMALL_THRESHOLD) {
            return 0;
        } else if (blocks <= ADAPTIVE_MEDIUM_THRESHOLD) {
            return ADAPTIVE_MEDIUM_MIN_DELAY;
        } else if (blocks <= ADAPTIVE_LARGE_THRESHOLD) {
            return ADAPTIVE_LARGE_MIN_DELAY;
        } else {
            return ADAPTIVE_HUGE_MIN_DELAY;
        }
    }
}
