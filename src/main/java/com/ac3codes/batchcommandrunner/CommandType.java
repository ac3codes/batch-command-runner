package com.ac3codes.batchcommandrunner;

/**
 * Classification of a batch command line, used to decide whether Heavy Command Protection's
 * extra minimum delay applies, and which per-type minimum to use.
 */
public enum CommandType {
    NORMAL,
    FILL,
    CLONE,
    PLACE,
    SUMMON
}
