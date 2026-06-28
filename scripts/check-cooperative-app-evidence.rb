#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "open3"

exit 0 unless ENV.fetch("GITHUB_EVENT_NAME", "") == "pull_request"

event = JSON.parse(File.read(ENV.fetch("GITHUB_EVENT_PATH")))
pr = event.fetch("pull_request")
body = pr["body"].to_s
base_sha = ENV.fetch("PR_BASE_SHA", pr.fetch("base").fetch("sha"))
head_sha = ENV.fetch("PR_HEAD_SHA", pr.fetch("head").fetch("sha"))

def git(*args)
  stdout, stderr, status = Open3.capture3("git", *args)
  raise "git #{args.join(' ')} failed: #{stderr}" unless status.success?

  stdout
end

def file_at(rev, path)
  git("show", "#{rev}:#{path}")
rescue RuntimeError
  ""
end

def markdown_section(text, prefix)
  lines = text.lines
  start = lines.index { |line| line.start_with?(prefix) }
  return "" if start.nil?

  finish = lines[(start + 1)..]&.index { |line| line.start_with?("## ") }
  stop = finish.nil? ? lines.length : start + 1 + finish
  lines[start...stop].join
end

def swift_default_bundle_ids(text)
  match = text.match(/public static let defaultBundleIDs = \[(.*?)^\s*\]/m)
  match ? match[1] : ""
end

changed_files = git("diff", "--name-only", "#{base_sha}...#{head_sha}").lines.map(&:chomp)
allowlist_changed = changed_files.include?("docs/cooperative-apps.yml")

if changed_files.include?("Sources/ollyDSL/CooperativeApps.swift")
  allowlist_changed ||= swift_default_bundle_ids(file_at(base_sha, "Sources/ollyDSL/CooperativeApps.swift")) !=
                         swift_default_bundle_ids(file_at(head_sha, "Sources/ollyDSL/CooperativeApps.swift"))
end

if changed_files.include?("NORTHSTAR.md")
  allowlist_changed ||= markdown_section(file_at(base_sha, "NORTHSTAR.md"), "## 7b.") !=
                         markdown_section(file_at(head_sha, "NORTHSTAR.md"), "## 7b.")
end
exit 0 unless allowlist_changed

evidence = body.match?(/Evidence[^\n]*(https?:\/\/\S+|#\d+|attached screenshot|attached recording|repro steps)/i)
exit 0 if evidence

warn "Cooperative-app allowlist changed; add Evidence with a screenshot, recording, repro steps, issue, or URL"
exit 1
