package com.example.nflsimtext.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The status and navigation bar icons follow the app's theme, not the
 * phone's: on a navy ground they have to be light even when the phone is in
 * light mode. A screen that is always dark - the title screen - asks for
 * light icons while it shows by calling this with true, and false on leaving.
 */
val LocalDarkBars = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
