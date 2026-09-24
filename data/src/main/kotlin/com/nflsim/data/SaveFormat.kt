package com.nflsim.data

/**
 * Save-format version. Bump this whenever the shape of the League object
 * changes, and add a migration step in the same commit - see docs/SPEC.md
 * section 9.1. A dynasty game that eats saves on update is a dead game.
 */
const val CURRENT_SAVE_VERSION: Int = 13
