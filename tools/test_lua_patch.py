#!/usr/bin/env python3
"""
Offline test for rom/autorun/wirelessperipherals.lua (the nested peripheral-hub patch).

Loads the *real* CC: Tweaked peripheral API, optionally the *real* CC: Modern autorun patch, then ours, on top of a
mocked native peripheral layer that mimics a CC: Modern server: a virtual "internal" hub containing "drive" and
"modem", where "modem" is itself a peripheral_hub exposing a wirelessly linked chest.

Usage (needs `pip install lupa`):
  python3 tools/test_lua_patch.py <cc rom/apis/peripheral.lua> <our wirelessperipherals.lua> [<cc_modern.lua>]
"""
import sys
import lupa

MOCK = r"""
local function make(types, methods, hub)
  local p = { types = types, methods = methods or {} }
  if hub then
    p.types[#p.types + 1] = "peripheral_hub"
    local m = p.methods
    m.getNamesRemote = function() local r = {} for k in pairs(hub) do r[#r + 1] = k end table.sort(r) return r end
    m.isPresentRemote = function(n) return hub[n] ~= nil end
    m.getTypeRemote = function(n) if hub[n] then return table.unpack(hub[n].types) end end
    m.hasTypeRemote = function(n, t) local q = hub[n] if not q then return nil end
      for _, x in ipairs(q.types) do if x == t then return true end end return false end
    m.getMethodsRemote = function(n) local q = hub[n] if not q then return nil end
      local r = {} for k in pairs(q.methods) do r[#r + 1] = k end table.sort(r) return r end
    m.callRemote = function(n, method, ...) local q = hub[n] if not q then error("No peripheral: " .. n) end
      local f = q.methods[method] if not f then error("No such method " .. method) end return f(...) end
  end
  return p
end

local chest = make({ "minecraft:chest", "inventory" }, { size = function() return 27 end })
local server_modem = make({ "modem" }, { isWireless = function() return true end }, { ["minecraft:chest_0"] = chest })
local drive = make({ "drive" }, { isDiskPresent = function() return false end })

TOP_LEVEL = {
  back = make({ "monitor" }, { getSize = function() return 51, 19 end }),
}
if WITH_INTERNAL then
  -- CC: Modern injects "internal" at the native PeripheralAPI level via a Java mixin.
  TOP_LEVEL.internal = make({ "server_internals" }, {}, { drive = drive, modem = server_modem })
end
if WITH_SIDE_MODEM then
  TOP_LEVEL.top = make({ "modem" }, { isWireless = function() return true end }, { ["minecraft:barrel_3"] = chest })
end

native = {}
function native.isPresent(n) return TOP_LEVEL[n] ~= nil end
function native.getType(n) local p = TOP_LEVEL[n] if p then return table.unpack(p.types) end end
function native.hasType(n, t) local p = TOP_LEVEL[n] if not p then return nil end
  for _, x in ipairs(p.types) do if x == t then return true end end return false end
function native.getMethods(n) local p = TOP_LEVEL[n] if not p then return nil end
  local r = {} for k in pairs(p.methods) do r[#r + 1] = k end table.sort(r) return r end
function native.call(n, m, ...) local p = TOP_LEVEL[n] if not p then error("No such peripheral", 2) end
  local f = p.methods[m] if not f then error("No such method " .. m, 2) end return f(...) end
"""

TESTS = r"""
local function has(list, v) for _, x in ipairs(list) do if x == v then return true end end return false end
local function count(list, v) local c = 0 for _, x in ipairs(list) do if x == v then c = c + 1 end end return c end

local names = peripheral.getNames()
assert(has(names, "back"), "side peripheral missing")
for _, n in ipairs(names) do assert(count(names, n) == 1, "duplicate name " .. n) end

if WITH_INTERNAL then
  assert(has(names, "minecraft:chest_0"), "nested chest missing from getNames: " .. table.concat(names, ","))
  assert(peripheral.isPresent("minecraft:chest_0"), "isPresent")
  assert(peripheral.getType("minecraft:chest_0") == "minecraft:chest", "getType")
  assert(peripheral.hasType("minecraft:chest_0", "inventory") == true, "hasType")
  local chest = assert(peripheral.wrap("minecraft:chest_0"), "wrap")
  assert(chest.size() == 27, "call through wrap")
  assert(peripheral.getName(chest) == "minecraft:chest_0", "getName")
  local found = false
  for _, p in ipairs({ peripheral.find("inventory") }) do
    if peripheral.getName(p) == "minecraft:chest_0" then found = true end
  end
  assert(found, "find")
  -- The server's own modem is still usable as before.
  assert(peripheral.wrap("modem").isWireless(), "modem still wraps")
else
  assert(not has(names, "minecraft:chest_0"), "nothing nested without internal hub")
end

if WITH_SIDE_MODEM then
  assert(has(names, "minecraft:barrel_3"), "side modem remote missing")
  assert(peripheral.wrap("minecraft:barrel_3").size() == 27, "side modem remote call")
end

-- Unknown names behave exactly like the stock API (returns nil, no error).
local ok, res = pcall(peripheral.call, "nope", "x")
assert(ok and res == nil, "call(nope) should return nil like stock: " .. tostring(res))
assert(peripheral.isPresent("nope") == false, "isPresent(nope)")
assert(peripheral.getType("nope") == nil, "getType(nope)")
return "ok"
"""


def run(stock_src, ours_src, modern_src, with_internal, with_side_modem):
    lua = lupa.LuaRuntime(unpack_returned_tuples=True)
    g = lua.globals()
    g.WITH_INTERNAL = with_internal
    g.WITH_SIDE_MODEM = with_side_modem
    lua.execute(MOCK)
    lua.execute(r"""
      rs = { getSides = function() return { "bottom", "top", "back", "front", "right", "left" } end }
      function sleep() end
      local expect_mod = { expect = function(_, v) return v end }
      function dofile(path)
        if path:find("expect") then return expect_mod end
        error("unexpected dofile " .. path)
      end
      function load_api(src, name)
        -- Mirrors os.loadAPI: globals defined by the file become the API table, and "peripheral" resolves through
        -- _G (native while loading, the API table afterwards).
        peripheral = native
        local env = setmetatable({}, { __index = _G })
        assert(load(src, "@" .. name, "t", env))()
        local api = {}
        for k, v in pairs(env) do api[k] = v end
        peripheral = api
      end
      function run_file(src, name) assert(load(src, "@" .. name, "t", _G))() end
    """)
    g.load_api(stock_src, "peripheral.lua")
    if modern_src is not None:
        g.run_file(modern_src, "cc_modern.lua")
    g.run_file(ours_src, "wirelessperipherals.lua")
    g.run_file(ours_src, "wirelessperipherals.lua")  # running twice must be harmless
    return lua.execute(TESTS)


def main():
    stock = open(sys.argv[1]).read()
    ours = open(sys.argv[2]).read()
    modern = open(sys.argv[3]).read() if len(sys.argv) > 3 else None
    cases = [(False, False), (False, True)]
    if modern is not None:
        cases += [(True, False), (True, True)]
    for with_internal, with_side in cases:
        result = run(stock, ours, modern if with_internal else None, with_internal, with_side)
        print(f"internal={with_internal!s:5} side_modem={with_side!s:5} -> {result}")
    print("All Lua patch tests passed")


if __name__ == "__main__":
    main()
