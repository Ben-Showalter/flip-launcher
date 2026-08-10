package com.flipos.launcher.ui

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * A short, cheap item animator for the vertical list screens: quick
 * insert/remove/move so building or paging a list feels alive, but no change
 * cross-fade (rows rebind on every settings refresh and cross-fading them just
 * flickers). Durations are kept small so it stays smooth on weak chipsets.
 */
fun listItemAnimator(): RecyclerView.ItemAnimator = DefaultItemAnimator().apply {
    addDuration = 120
    removeDuration = 120
    moveDuration = 150
    changeDuration = 0
    supportsChangeAnimations = false
}
