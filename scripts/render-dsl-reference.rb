#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "uri"

if ARGV.length != 2
  warn "usage: render-dsl-reference.rb ollyDSL.symbols.json docs/dsl-reference.md"
  exit 64
end

symbol_graph_path, output_path = ARGV
source_order = {
  "Animation.swift" => "Animation",
  "Config.swift" => "Config",
  "Hooks.swift" => "Hooks",
  "Keybind.swift" => "Keybinds",
  "NamedTag.swift" => "Workspaces",
  "EngineDSL.swift" => "Engines",
  "Gestures.swift" => "Gestures",
  "RulePredicate.swift" => "Rule Predicates",
  "Rule.swift" => "Rules",
  "SafeZones.swift" => "Safe Zones",
  "CooperativeApps.swift" => "Cooperative Apps",
  "RawDSL.swift" => "Raw Escape Hatches"
}

def source_basename(symbol)
  uri = symbol.dig("location", "uri")
  return nil unless uri

  File.basename(URI(uri).path)
rescue StandardError
  File.basename(uri.sub("file://", ""))
end

def declaration(symbol)
  symbol.fetch("declarationFragments", []).map { |fragment| fragment.fetch("spelling") }.join
end

def doc_fields(symbol)
  lines = symbol.dig("docComment", "lines")&.map { |line| line.fetch("text") } || []
  fields = {}
  lines.each do |line|
    key, value = line.split(":", 2)
    next unless key && value

    fields[key] = value.strip
  end
  fields
end

data = JSON.parse(File.read(symbol_graph_path))
symbols = []
data.fetch("symbols").each do |symbol|
  basename = source_basename(symbol)
  next unless source_order.key?(basename)

  fields = doc_fields(symbol)
  next unless %w[Purpose Parameters Example See\ also].all? { |field| fields.key?(field) }

  symbols << {
    basename: basename,
    line: symbol.dig("location", "position", "line") || 0,
    title: symbol.dig("names", "title"),
    declaration: declaration(symbol),
    fields: fields
  }
end

symbols.sort_by! { |symbol| [source_order.keys.index(symbol[:basename]), symbol[:line], symbol[:title]] }

content = +"# olly DSL Reference\n\n"
content << "Generated from the `ollyDSL` DocC symbol graph. Do not edit by hand.\n\n"

symbols.group_by { |symbol| symbol[:basename] }.each do |basename, group|
  content << "## #{source_order.fetch(basename)}\n\n"
  group.each do |symbol|
    content << "### #{symbol[:title]}\n\n"
    content << "`#{symbol[:declaration]}`\n\n"
    content << "- Purpose: #{symbol[:fields].fetch('Purpose')}\n"
    content << "- Parameters: #{symbol[:fields].fetch('Parameters')}\n"
    content << "- Example: #{symbol[:fields].fetch('Example')}\n"
    content << "- See also: #{symbol[:fields].fetch('See also')}\n\n"
  end
end

File.write(output_path, content)
