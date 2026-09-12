# Mod handshake check

`app/src/test/resources/modded-save.lua` is not written by hand. It is the
output of TM32 Double Team's own `Boxes.ensure` wrapper, run in Lua over a
stock twelve-box save and written back out in `SaveSerializer`'s grammar.

`ModdedSaveFileTest` then reads that file with this app's parser. That is the
only check in the suite that closes the loop — everything else tests the app
against a save shaped the way this app *believes* the mod shapes one.

To regenerate it after a change to the mod, put the mod's `main.lua` in a
`TM32_Double-Team/` folder beside these scripts and run:

    lua5.4 emit.lua
    cp modded-save.lua ../../app/src/test/resources/modded-save.lua

`harness.lua` is the shorter version: it prints what the mod did to a save
rather than writing one out, which is what to reach for when the mod itself is
what is in question.

Both stub only the parts of Gen1Recomp the mod's storage half touches. The UI
half is deliberately absent, so these also check the mod degrades to the
storage change alone rather than erroring when the UI modules it wants are not
there.
