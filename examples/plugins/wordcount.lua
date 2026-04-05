-- show word count on file open
shed.on("BufOpen", function()
  local text = shed.get_text()
  local _, count = text:gsub("%S+", "")
  shed.message(count .. " words")
end)
