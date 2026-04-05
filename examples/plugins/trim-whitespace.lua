-- trim trailing whitespace on save
shed.on("BufWrite", function()
  for i = 1, shed.line_count() do
    local line = shed.get_line(i)
    local trimmed = line:gsub("%s+$", "")
    if trimmed ~= line then
      shed.set_line(i, trimmed)
    end
  end
end)
