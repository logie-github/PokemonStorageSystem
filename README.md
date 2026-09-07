# Pokémon Save Reader

A read-only Android browser for Gen 1 Recomp and Gen2Recomped saves through
Shizuku. Browse trainer saves, party Pokémon, and PC boxes. No export or transfer.

Sources inspected: bryanthaboi/gen1recomp at fdd1d61ea6f5f27f8edf80cbdbd4d9b68cb7f49b
and UNDERdecoded/Gen2Recomped at 1b818b41e4c71ffc72d53bce9211d9527c4a6b1e.
Their conf.lua, SaveData.lua, SaveSerializer.lua and Boxes.lua define this format.

Both use files/save/pokemon-love2d within Android/data/<package>.
The current Gen 1 package is com.theboisclub.pokemonred.androidfixes (the older
com.theboisclub.pokemonred is also checked); Gen 2 uses com.underdecodedhd.gen2recomp.
Slots are saves/<version>/slotN.lua, plus legacy save.lua and save_<version>.lua.
The options.lua saveSlots registry supplies custom labels.

The literal-only parser handles explicit numeric keys without executing Lua.
Party is stored in party; PC storage uses boxes, or the legacy box array.
Species IDs are displayed as stored. Backup recovery and custom package/identity
overrides are not implemented. No device validation has been performed.

## Build

Requires JDK 17 and Android SDK 35.

```sh
./gradlew testDebugUnitTest assembleDebug
```
