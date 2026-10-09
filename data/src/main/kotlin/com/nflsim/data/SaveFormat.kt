package com.nflsim.data

/**
 * Save-format version. Bump this whenever the shape of the League object
 * changes, and add its step to the chain in data/migration/Migrations.kt in
 * the same commit - see docs/SPEC.md section 9.1, and the test that fails
 * when a version has no step. A dynasty game that eats saves on update is a
 * dead game.
 */
const val CURRENT_SAVE_VERSION: Int = 58
