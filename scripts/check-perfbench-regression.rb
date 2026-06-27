#!/usr/bin/env ruby
require "json"

def usage
  "usage: check-perfbench-regression.rb [--threshold N] baseline.json current.json"
end

threshold = 0.10
paths = []

until ARGV.empty?
  argument = ARGV.shift
  case argument
  when "--threshold"
    value = ARGV.shift
    abort usage unless value
    threshold = Float(value)
  else
    paths << argument
  end
end

abort usage unless paths.length == 2
abort "--threshold must be >= 0" if threshold.negative?

def scenario_p95s(path)
  report = JSON.parse(File.read(path))
  scenarios = report.fetch("scenarios")
  scenarios.each_with_object({}) do |scenario, index|
    name = scenario.fetch("name")
    p95 = scenario.fetch("p95")
    abort "#{path}: duplicate scenario #{name}" if index.key?(name)
    abort "#{path}: #{name} p95 must be numeric" unless p95.is_a?(Numeric)
    index[name] = p95.to_f
  end
end

baseline = scenario_p95s(paths[0])
current = scenario_p95s(paths[1])
failures = []

(baseline.keys - current.keys).sort.each do |name|
  failures << "#{name}: missing from current report"
end

current.sort.each do |name, p95|
  unless baseline.key?(name)
    failures << "#{name}: missing from baseline"
    next
  end

  baseline_p95 = baseline.fetch(name)
  limit = baseline_p95 * (1.0 + threshold)
  next unless p95 > limit

  failures << format(
    "%<name>s: p95 %<p95>.3f ms > %<limit>.3f ms baseline + %<threshold>.1f%% (baseline %<baseline_p95>.3f ms)",
    name: name,
    p95: p95,
    limit: limit,
    threshold: threshold * 100,
    baseline_p95: baseline_p95
  )
end

if failures.any?
  warn "PerfBench regression check failed:"
  failures.each { |failure| warn "  #{failure}" }
  exit 1
end

puts format("PerfBench p95 check passed (%d scenarios, threshold %.1f%%)", current.length, threshold * 100)
