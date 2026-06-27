#!/usr/bin/env ruby
# frozen_string_literal: true

FILES = %w[
  Sources/ollyDSL/Animation.swift
  Sources/ollyDSL/Config.swift
  Sources/ollyDSL/CooperativeApps.swift
  Sources/ollyDSL/EngineDSL.swift
  Sources/ollyDSL/Hooks.swift
  Sources/ollyDSL/Keybind.swift
  Sources/ollyDSL/NamedTag.swift
  Sources/ollyDSL/RawDSL.swift
  Sources/ollyDSL/Rule.swift
  Sources/ollyDSL/RulePredicate.swift
  Sources/ollyDSL/SafeZones.swift
].freeze

REQUIRED_FIELDS = ["Purpose:", "Parameters:", "Example:", "See also:"].freeze
DECLARATION = /^public (struct|enum|func)\b/

failures = []

FILES.each do |path|
  lines = File.readlines(path, chomp: true)
  lines.each_with_index do |line, index|
    next unless line.match?(DECLARATION)

    cursor = index - 1
    cursor -= 1 while cursor >= 0 && lines[cursor].match?(/^@/)

    docs = []
    while cursor >= 0 && lines[cursor].lstrip.start_with?("///")
      docs.unshift(lines[cursor])
      cursor -= 1
    end

    missing = REQUIRED_FIELDS.reject { |field| docs.any? { |doc| doc.include?(field) } }
    next if missing.empty?

    failures << "#{path}:#{index + 1} missing #{missing.join(', ')}"
  end
end

if failures.any?
  warn "DSL doc comment check failed:"
  failures.each { |failure| warn "  #{failure}" }
  exit 1
end
