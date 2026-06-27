#!/usr/bin/env ruby
require "json"
require "net/http"
require "open3"
require "time"
require "uri"

def fetch_json(url, headers = {})
  uri = URI(url)
  request = Net::HTTP::Get.new(uri)
  headers.each { |key, value| request[key] = value }
  response = Net::HTTP.start(uri.hostname, uri.port, use_ssl: uri.scheme == "https") do |http|
    http.request(request)
  end
  raise "#{url} returned #{response.code}" unless response.is_a?(Net::HTTPSuccess)

  JSON.parse(response.body)
end

def repository_slug
  env_slug = ENV["OLLY_GITHUB_REPOSITORY"] || ENV["GITHUB_REPOSITORY"]
  return env_slug unless env_slug.to_s.empty?

  remote, = Open3.capture2("git", "config", "--get", "remote.origin.url")
  match = remote.strip.match(%r{github.com[:/](.+?)(?:\.git)?$})
  raise "Set OLLY_GITHUB_REPOSITORY=owner/repo" unless match

  match[1]
end

def github_headers
  headers = {
    "Accept" => "application/vnd.github+json",
    "User-Agent" => "olly-adoption-metrics"
  }
  token = ENV["GITHUB_TOKEN"].to_s
  headers["Authorization"] = "Bearer #{token}" unless token.empty?
  headers
end

def homebrew_count(cask_token, period)
  data = fetch_json("https://formulae.brew.sh/api/analytics/cask-install/homebrew-cask/#{period}.json")
  entry = data.fetch("formulae", []).find { |name, _records| name == cask_token }
  return nil unless entry

  entry[1].first.fetch("count").to_i
end

def github_metrics(repo)
  repo_data = fetch_json("https://api.github.com/repos/#{repo}", github_headers)
  {
    repository: repo,
    stargazers_count: repo_data.fetch("stargazers_count"),
    forks_count: repo_data.fetch("forks_count"),
    open_issues_count: repo_data.fetch("open_issues_count")
  }
rescue StandardError => error
  {
    repository: repo,
    stargazers_count: nil,
    forks_count: nil,
    open_issues_count: nil,
    error: error.message
  }
end

def homebrew_metrics(cask_token)
  {
    cask: cask_token,
    installs_30d: homebrew_count(cask_token, "30d"),
    installs_90d: homebrew_count(cask_token, "90d"),
    installs_365d: homebrew_count(cask_token, "365d")
  }
rescue StandardError => error
  {
    cask: cask_token,
    installs_30d: nil,
    installs_90d: nil,
    installs_365d: nil,
    error: error.message
  }
end

repo = repository_slug
cask_token = ENV.fetch("HOMEBREW_CASK_TOKEN", "olly")
raycast_extension = ENV.fetch("RAYCAST_EXTENSION", "olly")

report = {
  collected_at: Time.now.utc.iso8601,
  github: github_metrics(repo),
  homebrew: homebrew_metrics(cask_token),
  raycast: {
    extension: raycast_extension,
    installs: nil,
    collection: "manual Raycast developer/store dashboard check"
  }
}

puts JSON.pretty_generate(report)
