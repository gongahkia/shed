-- run go tests on save for *_test.go files
shed.on("BufWrite", function()
  local path = shed.file_path()
  if path:match("_test%.go$") then
    local output = shed.shell("go test ./... 2>&1 | tail -1")
    shed.message(output:gsub("\n", ""))
  end
end)
