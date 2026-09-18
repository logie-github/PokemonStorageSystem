package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen2Mail

/**
 * The PC MAILBOX: a save's own letters, detached from any Pokémon.
 *
 * `MailboxPC` (`engine/pokemon/mail.asm`), listed the way it lists them —
 * by author, newest last — with READ and ATTACH behind a tap on one. PUT IN
 * PACK is not offered; see [com.logie.gen1storage.transfer.MailEngine]'s own
 * note on why.
 */
@Composable
fun MailboxScreen(state: UiState, model: StorageViewModel) {
    val key = state.activeSaveKey
    val save = state.save(key)?.save
    if (key == null || save == null) {
        val only = rememberCursorLayer(1) { model.back() }
        ScreenColumn {
            item { Gen1Frame(Modifier.wrapContentWidth()) { GbText("NO CART IN THE MACHINE.") } }
            item { Gen1BoxButton("BACK", { model.back() }, selected = only == 0) }
        }
        return
    }

    val letters = Gen2Mail.mailbox(save.root)
    fun open(index: Int) {
        if (index >= letters.size) model.back()
        else model.prompt(Prompt.MailboxAction(key, index + 1))
    }
    val cursor = rememberCursorLayer(letters.size + 1) { open(it) }

    ScreenColumn {
        item {
            Gen1Frame(
                Modifier.wrapContentWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                GbText("MAILBOX  ${letters.size}/${Gen2Mail.MAILBOX_CAPACITY}")
            }
        }
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                if (letters.isEmpty()) {
                    GbText("THERE'S NO MAIL HERE.", style = Gen1TextSmall)
                }
                letters.forEachIndexed { index, letter ->
                    Gen1MenuRow(
                        // MailboxPC_PrintMailAuthor: the author, not the
                        // message — a blank one (a scripted gift with no OT
                        // resolved) falls back to what it is at all.
                        letter.author.ifBlank { itemLabel(letter.type) }.uppercase(),
                        selected = cursor == index,
                        onSelect = {},
                        onConfirm = { open(index) },
                    )
                }
            }
        }
        item { Gen1BoxButton("BACK", { model.back() }, selected = cursor == letters.size) }
    }
}

/**
 * ATTACH MAIL's party list: every Pokémon in the loaded save, tapped to give
 * it [letter]. Refusals — an egg, one already holding something — come back
 * from [MailEngine][com.logie.gen1storage.transfer.MailEngine] exactly as
 * any other transfer refusal does, rather than being duplicated here.
 */
@Composable
fun AttachMailPicker(
    key: String,
    mailboxIndex: Int,
    party: List<Gen1Pokemon>,
    onChoose: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    fun choose(index: Int) {
        val mon = party.getOrNull(index) ?: return
        if (!mon.isEgg && mon.heldItem == null) onChoose(index)
    }
    val cursor = rememberCursorLayer(party.size + 1) { index ->
        if (index >= party.size) onCancel() else choose(index)
    }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = true, menuSide = true),
    ) {
        Gen1Frame(Modifier.gen1MaxWidth().wrapContentWidth().padding(top = gen1Dp(5))) {
            GbText("GIVE IT TO WHOM?")
            Spacer(Modifier.height(gen1Dp(2)))
            LazyColumn(
                Modifier.heightIn(max = 320.dp),
                userScrollEnabled = !LocalGen1Swipe.current,
            ) {
                itemsIndexed(party) { index, mon ->
                    Gen1MenuRow(
                        mon.displayName.uppercase(),
                        selected = cursor == index,
                        onSelect = {},
                        onConfirm = { onChoose(index) },
                        // The two the real menu loops back over rather than
                        // letting the player try: an egg has no held-item
                        // slot to speak of, and one already carrying
                        // something has nowhere to put a second thing.
                        enabled = !mon.isEgg && mon.heldItem == null,
                        trailing = when {
                            mon.isEgg -> "EGG"
                            mon.heldItem != null -> "HOLDING"
                            else -> null
                        },
                    )
                }
                item { Gen1MenuRow("CANCEL", cursor == party.size, {}, onCancel) }
            }
        }
    }
}
