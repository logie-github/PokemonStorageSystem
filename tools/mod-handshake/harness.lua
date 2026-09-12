-- A stub of just enough Gen1Recomp for the mod's storage half to run.
package.preload["src.pokemon.Boxes"] = function()
  local Boxes = { COUNT = 12, CAPACITY = 20 }
  -- Stock ensure: makes the boxes only when there are none at all.
  function Boxes.ensure(save)
    if type(save.boxes) ~= "table" then
      save.boxes = {}
      for i = 1, Boxes.COUNT do save.boxes[i] = {} end
      save.currentBox = 1
    end
    return save.boxes
  end
  return Boxes
end
-- The UI half is absent on purpose: the mod must degrade to the storage
-- change alone rather than erroring, which is the path a headless check takes.
for _, name in ipairs({ "src.ui.Menu", "src.core.StateStack", "src.render.Font",
                        "src.render.TextBox", "src.core.Strings" }) do
  package.preload[name] = function() error("absent") end
end

local Boxes = require("src.pokemon.Boxes")
local entry = dofile("TM32_Double-Team/main.lua")
entry({ id = "tm32_double_team" })

-- A save as it is before the PC has ever been opened with the mod on.
local save = { meta = { format = 5, playthroughId = "quiet-forest-dawn" }, boxes = {}, currentBox = 9 }
for i = 1, 12 do save.boxes[i] = {} end
save.boxes[12] = { { species = "ODDISH" } }

Boxes.ensure(save)

print("Boxes.COUNT           =", Boxes.COUNT)
print("boxes after ensure    =", #save.boxes)
print("currentBox            =", save.currentBox)
local card = save.meta.mods and save.meta.mods.tm32_double_team
print("declared name         =", card and card.name)
print("declared version      =", card and card.version)
print("declared boxCount     =", card and card.boxCount)
print("declared boxCapacity  =", card and card.boxCapacity)
print("box 12 kept its mon   =", #save.boxes[12])
print("box 24 exists         =", type(save.boxes[24]))
print("box 25 exists         =", type(save.boxes[25]))

-- Running it twice must not double anything up.
Boxes.ensure(save)
local n = 0
for _ in pairs(save.meta.mods) do n = n + 1 end
print("mods entries after 2x =", n)

-- A save whose mod list is a plain array of ids is left alone.
local listy = { meta = { mods = { "some_other_mod" } }, boxes = {}, currentBox = 1 }
for i = 1, 12 do listy.boxes[i] = {} end
Boxes.ensure(listy)
print("array list untouched  =", listy.meta.mods[1], listy.meta.mods.tm32_double_team == nil)
