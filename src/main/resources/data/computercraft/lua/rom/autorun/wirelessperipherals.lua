-- CC: Wireless Peripherals
--
-- CC's peripheral API only looks for remote peripherals on hubs attached to a computer *side*. Some hosts nest their
-- wireless modem one level deeper (e.g. CC: Modern servers expose it as "modem" inside the virtual "internal" hub),
-- so peripherals linked to that modem would be invisible. This wraps the peripheral API so it also searches any
-- non-side peripheral hub. Runs after cc_modern.lua (autorun files run alphabetically) and does nothing extra when
-- there are no nested hubs.

if type(peripheral) ~= "table" or peripheral.__ccwpNestedPatched then return end

local unpack = table.unpack
local sides = {}
for _, side in ipairs(rs.getSides()) do sides[side] = true end

local old_get_names = peripheral.getNames
local old_is_present = peripheral.isPresent
local old_get_type = peripheral.getType
local old_has_type = peripheral.hasType
local old_get_methods = peripheral.getMethods
local old_call = peripheral.call

-- Peripheral hubs the stock API does not search (anything that is not a side).
local function nested_hubs()
    local hubs = {}
    for _, name in ipairs(old_get_names()) do
        if not sides[name] then
            local ok, is_hub = pcall(old_has_type, name, "peripheral_hub")
            if ok and is_hub then hubs[#hubs + 1] = name end
        end
    end
    return hubs
end

local hub_cache = {} -- remote name -> hub it was last found on

local function is_on(hub, name)
    local ok, present = pcall(old_call, hub, "isPresentRemote", name)
    return ok and present == true
end

local function find_hub(name)
    local cached = hub_cache[name]
    if cached then
        if is_on(cached, name) then return cached end
        hub_cache[name] = nil
    end
    for _, hub in ipairs(nested_hubs()) do
        if is_on(hub, name) then
            hub_cache[name] = hub
            return hub
        end
    end
    return nil
end

-- True when the original API already knows about this peripheral (or the argument isn't a name).
local function native(name)
    return type(name) ~= "string" or old_is_present(name)
end

peripheral.getNames = function()
    local results, seen = {}, {}
    for _, name in ipairs(old_get_names()) do
        if not seen[name] then seen[name] = true; results[#results + 1] = name end
    end
    for _, hub in ipairs(nested_hubs()) do
        local ok, remote = pcall(old_call, hub, "getNamesRemote")
        if ok and type(remote) == "table" then
            for _, name in ipairs(remote) do
                if not seen[name] then seen[name] = true; results[#results + 1] = name end
            end
        end
    end
    return results
end

peripheral.isPresent = function(name)
    if native(name) then return old_is_present(name) end
    return find_hub(name) ~= nil
end

peripheral.getType = function(name)
    if native(name) then return old_get_type(name) end
    local hub = find_hub(name)
    if hub then return old_call(hub, "getTypeRemote", name) end
    return nil
end

peripheral.hasType = function(name, peripheral_type)
    if native(name) then return old_has_type(name, peripheral_type) end
    local hub = find_hub(name)
    if hub then return old_call(hub, "hasTypeRemote", name, peripheral_type) end
    return nil
end

peripheral.getMethods = function(name)
    if native(name) then return old_get_methods(name) end
    local hub = find_hub(name)
    if hub then return old_call(hub, "getMethodsRemote", name) end
    return nil
end

peripheral.call = function(name, method, ...)
    if native(name) then return old_call(name, method, ...) end
    local hub = find_hub(name)
    if hub then return old_call(hub, "callRemote", name, method, ...) end
    return old_call(name, method, ...) -- unknown name: same result as the stock API
end

peripheral.__ccwpNestedPatched = true
