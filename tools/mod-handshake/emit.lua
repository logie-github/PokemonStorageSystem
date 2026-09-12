-- Runs the real mod over a realistic save and writes the result as Lua source,
-- so the app's own parser can be pointed at what the mod actually produces.
package.preload["src.pokemon.Boxes"] = function()
  local Boxes = { COUNT = 12, CAPACITY = 20 }
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
for _, name in ipairs({ "src.ui.Menu", "src.core.StateStack", "src.render.Font",
                        "src.render.TextBox", "src.core.Strings" }) do
  package.preload[name] = function() error("absent") end
end

local Boxes = require("src.pokemon.Boxes")
dofile("TM32_Double-Team/main.lua")({ id = "tm32_double_team" })

local function mon(species, nick, level)
  return {
    species = species, nickname = nick, level = level, hp = 20, maxHp = 20,
    exp = 1000, otName = "ASH", otId = 12345,
    dvs = { 15, 12, 10, 9 },
    statExp = { 0, 0, 0, 0, 0 },
    moves = { { id = "TACKLE", pp = 35 } },
    stats = { hp = 20, attack = 12, defense = 11, speed = 13, special = 14 },
  }
end

local save = {
  version = "red",
  meta = { format = 5, playthroughId = "quiet-forest-dawn" },
  player = { name = "ASH", rival = "BLUE", id = 12345, map = "PALLET_TOWN", x = 5, y = 6, facing = "down" },
  party = { mon("PIKACHU", "SPARKY", 25) },
  inventory = { BOULDERBADGE = 1, POTION = 3 },
  pcItems = { POTION = 1 },
  pokedex = { owned = { PIKACHU = true }, seen = { PIKACHU = true } },
  playTime = 7265.5,
  boxes = {},
  currentBox = 20,
}
for i = 1, 12 do save.boxes[i] = {} end
save.boxes[12] = { mon("ODDISH", "TWELVE", 8) }

Boxes.ensure(save)
save.boxes[24] = { mon("ABRA", "LAST", 16) }

-- Gen1Recomp's own grammar: `return { ... }`, keys bare where they can be.
local function quote(s) return '"' .. s:gsub('\\', '\\\\'):gsub('"', '\\"') .. '"' end
-- SaveSerializer's grammar exactly: every entry is a key, arrays included, so
-- an array element is written [1] = value rather than bare. A bare element is
-- what Gen1Recomp's own reader rejects.
local function emit(value, indent)
  local pad = string.rep("  ", indent)
  local inner = string.rep("  ", indent + 1)
  local kind = type(value)
  if kind == "string" then return quote(value) end
  if kind == "number" then
    return value == math.floor(value) and string.format("%d", value) or tostring(value)
  end
  if kind == "boolean" then return tostring(value) end
  if kind ~= "table" then return "nil" end
  local keys = {}
  for k in pairs(value) do keys[#keys + 1] = k end
  table.sort(keys, function(a, b)
    if type(a) == type(b) then return a < b end
    return type(a) == "number"
  end)
  local parts = {}
  for _, k in ipairs(keys) do
    local name
    if type(k) == "string" and k:match("^[%a_][%w_]*$") then
      name = k
    else
      name = "[" .. emit(k, indent + 1) .. "]"
    end
    parts[#parts + 1] = inner .. name .. " = " .. emit(value[k], indent + 1)
  end
  if #parts == 0 then return "{}" end
  return "{\n" .. table.concat(parts, ",\n") .. ",\n" .. pad .. "}"
end

local out = io.open("modded-save.lua", "w")
out:write("return " .. emit(save, 0) .. "\n")
out:close()
print("wrote modded-save.lua")
