#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "open3"

event_path = ENV.fetch("GITHUB_EVENT_PATH")
event = JSON.parse(File.read(event_path))
pr = event.fetch("pull_request")
body = pr["body"].to_s
base_sha = ENV.fetch("PR_BASE_SHA", pr.fetch("base").fetch("sha"))
head_sha = ENV.fetch("PR_HEAD_SHA", pr.fetch("head").fetch("sha"))
failures = []

unless body.match?(/ref:N§\d+[a-z]?/i)
  failures << "PR body must cite NORTHSTAR sections with ref:N§..."
end

def git_show(rev, path)
  stdout, stderr, status = Open3.capture3("git", "show", "#{rev}:#{path}")
  raise "git show #{rev}:#{path} failed: #{stderr}" unless status.success?

  stdout
end

def section(text, prefix)
  lines = text.lines
  start = lines.index { |line| line.start_with?(prefix) }
  return "" if start.nil?

  finish = lines[(start + 1)..]&.index { |line| line.start_with?("## ") }
  stop = finish.nil? ? lines.length : start + 1 + finish
  lines[start...stop].join
end

base_locked = section(git_show(base_sha, "NORTHSTAR.md"), "## 4. Locked Decisions")
head_locked = section(git_show(head_sha, "NORTHSTAR.md"), "## 4. Locked Decisions")
locked_decisions_changed = base_locked != head_locked
rfc_issue = body.match?(/\bRFC\s*(?:issue\s*)?(?:#\d+|https:\/\/github\.com\/[^\/\s]+\/[^\/\s]+\/issues\/\d+)/i)

if locked_decisions_changed && !rfc_issue
  failures << "NORTHSTAR §4 locked decisions changed; link an RFC issue first"
end

if failures.any?
  warn "PR governance check failed:"
  failures.each { |failure| warn "  #{failure}" }
  exit 1
end
