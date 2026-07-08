#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "fileutils"
require "optparse"
require "time"

EXPECTED_LANGUAGES = %w[
  swift typescript javascript rust python go c cpp zig elixir kotlin csharp bash
  dockerfile sql dart haskell lua ruby terraform html css json
].freeze

options = { previous: nil, output: nil }
OptionParser.new do |parser|
  parser.on("--previous PATH") { |value| options[:previous] = value }
  parser.on("--output PATH") { |value| options[:output] = value }
end.parse!

abort "usage: lsp_smoke_merge.rb --output PATH [--previous PATH] INPUT..." unless options[:output] && !ARGV.empty?

results = ARGV.flat_map do |path|
  data = JSON.parse(File.read(path))
  data.fetch("results")
end

by_language = {}
results.each do |entry|
  language = entry.fetch("language")
  by_language[language] = entry
end

missing = EXPECTED_LANGUAGES - by_language.keys
abort "missing LSP smoke results: #{missing.join(", ")}" unless missing.empty?

empty_skip_reasons = by_language.values.select { |entry| entry.fetch("status") == "skip" && entry.fetch("reason").to_s.empty? }
abort "skip entries missing reason: #{empty_skip_reasons.map { |entry| entry.fetch("language") }.join(", ")}" unless empty_skip_reasons.empty?

failures = by_language.values.select { |entry| entry.fetch("status") == "fail" }

regressions = []
if options[:previous] && File.exist?(options[:previous])
  previous = JSON.parse(File.read(options[:previous])).fetch("results").to_h { |entry| [entry.fetch("language"), entry] }
  regressions = by_language.values.select do |entry|
    previous.dig(entry.fetch("language"), "status") == "ok" && entry.fetch("status") != "ok"
  end
end

counts = by_language.values.each_with_object(Hash.new(0)) { |entry, acc| acc[entry.fetch("status")] += 1 }
payload = {
  "generated_at" => Time.now.utc.iso8601,
  "summary" => {
    "ok" => counts["ok"],
    "skipped" => counts["skip"],
    "failed" => counts["fail"],
    "total" => by_language.length,
  },
  "results" => EXPECTED_LANGUAGES.map { |language| by_language.fetch(language) },
}

FileUtils.mkdir_p(File.dirname(options[:output]))
File.write(options[:output], JSON.pretty_generate(payload) + "\n")

unless failures.empty?
  warn "LSP smoke failures: #{failures.map { |entry| entry.fetch("language") }.join(", ")}"
  exit 1
end

unless regressions.empty?
  warn "LSP smoke regressions from ok: #{regressions.map { |entry| "#{entry.fetch("language")}=#{entry.fetch("status")}" }.join(", ")}"
  exit 1
end
