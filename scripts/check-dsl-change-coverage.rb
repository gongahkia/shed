#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "open3"

exit 0 unless ENV.fetch("GITHUB_EVENT_NAME", "") == "pull_request"

event = JSON.parse(File.read(ENV.fetch("GITHUB_EVENT_PATH")))
pr = event.fetch("pull_request")
base_sha = ENV.fetch("PR_BASE_SHA", pr.fetch("base").fetch("sha"))
head_sha = ENV.fetch("PR_HEAD_SHA", pr.fetch("head").fetch("sha"))

def git(*args)
  stdout, stderr, status = Open3.capture3("git", *args)
  raise "git #{args.join(' ')} failed: #{stderr}" unless status.success?

  stdout
end

changed_files = git("diff", "--name-only", "#{base_sha}...#{head_sha}").lines.map(&:chomp)
dsl_files = changed_files.grep(%r{\ASources/ollyDSL/.*\.swift\z})
exit 0 if dsl_files.empty?

examples_changed = changed_files.any? { |path| path.start_with?("examples/") }
diff = git("diff", "--unified=0", "#{base_sha}...#{head_sha}", "--", *dsl_files)
doc_comment_changed = diff.lines.any? { |line| line.match?(/\A[+-]\s*\/\/\//) }
failures = []

failures << "DSL source changed; update examples/ with a corresponding config entry" unless examples_changed
failures << "DSL source changed; update affected public doc comments" unless doc_comment_changed

if failures.any?
  warn "DSL change coverage check failed:"
  failures.each { |failure| warn "  #{failure}" }
  exit 1
end
