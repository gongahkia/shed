#!/usr/bin/env ruby
# frozen_string_literal: true

require "cgi"
require "fileutils"

if ARGV.length != 2
  warn "usage: render-docs-site.rb docs-dir site-dir"
  exit 64
end

docs_dir, site_dir = ARGV
docs_output_dir = File.join(site_dir, "docs")
FileUtils.mkdir_p(docs_output_dir)

DOC_GROUPS = {
  "Start" => %w[first-run architecture doctor],
  "Configuration" => %w[dsl-cookbook dsl-reference scratchpads multi-monitor],
  "Runtime" => %w[ipc hotkey-delegation menubar-notch-integration performance perfbench-results],
  "Extensions" => %w[plugin-authoring cooperative-apps layouts-research],
  "Release" => %w[demo-readiness release-readiness homebrew-cask-pr adoption-metrics ax-observer-wakeup-latency launch-post]
}.freeze

def slug(text)
  text.downcase.gsub(/[^a-z0-9]+/, "-").gsub(/^-|-$/, "")
end

def title_for(path)
  first_heading = File.readlines(path).find { |line| line.start_with?("# ") }
  return first_heading.sub(/^#\s+/, "").strip if first_heading

  File.basename(path, ".md").split("-").map(&:capitalize).join(" ")
end

def rewrite_href(href)
  return href if href.start_with?("http://", "https://", "#", "mailto:")

  href.sub(/\.md($|#)/, '.html\1')
end

def inline_markdown(text)
  escaped = CGI.escapeHTML(text)
  escaped = escaped.gsub(/`([^`]+)`/, '<code>\1</code>')
  escaped = escaped.gsub(/!\[([^\]]*)\]\(([^)]+)\)/) do
    alt = CGI.escapeHTML(Regexp.last_match(1))
    src = CGI.escapeHTML(rewrite_href(Regexp.last_match(2)))
    %(<img src="#{src}" alt="#{alt}">)
  end
  escaped.gsub(/\[([^\]]+)\]\(([^)]+)\)/) do
    label = Regexp.last_match(1)
    href = CGI.escapeHTML(rewrite_href(Regexp.last_match(2)))
    %(<a href="#{href}">#{label}</a>)
  end
end

def render_table(lines, index)
  header = lines[index].strip
  separator = lines[index + 1]&.strip
  return nil unless header.start_with?("|") && separator&.match?(/^\|?[\s:-]+\|/)

  rows = []
  cursor = index
  while cursor < lines.length && lines[cursor].strip.start_with?("|")
    rows << lines[cursor].strip.split("|").map(&:strip).reject(&:empty?)
    cursor += 1
  end
  head = rows.first || []
  body = rows.drop(2)
  html = +"<table><thead><tr>"
  head.each { |cell| html << "<th>#{inline_markdown(cell)}</th>" }
  html << "</tr></thead><tbody>"
  body.each do |row|
    html << "<tr>"
    row.each { |cell| html << "<td>#{inline_markdown(cell)}</td>" }
    html << "</tr>"
  end
  html << "</tbody></table>"
  [html, cursor]
end

def render_markdown(markdown)
  lines = markdown.lines
  html = +""
  paragraph = []
  list_type = nil
  in_code = false

  flush_paragraph = lambda do
    next if paragraph.empty?

    html << "<p>#{inline_markdown(paragraph.join(' ').strip)}</p>\n"
    paragraph.clear
  end

  close_list = lambda do
    next unless list_type

    html << "</#{list_type}>\n"
    list_type = nil
  end

  index = 0
  while index < lines.length
    line = lines[index].rstrip

    if in_code
      if line.start_with?("```")
        html << "</code></pre>\n"
        in_code = false
      else
        html << "#{CGI.escapeHTML(line)}\n"
      end
      index += 1
      next
    end

    if line.start_with?("```")
      flush_paragraph.call
      close_list.call
      language = CGI.escapeHTML(line.sub(/^```/, ""))
      html << %(<pre><code class="language-#{language}">)
      in_code = true
      index += 1
      next
    end

    if (table = render_table(lines, index))
      flush_paragraph.call
      close_list.call
      html << "#{table[0]}\n"
      index = table[1]
      next
    end

    if line.strip.empty?
      flush_paragraph.call
      close_list.call
    elsif (heading = line.match('\A(#{1,6})\s+(.+)\z'))
      flush_paragraph.call
      close_list.call
      level = heading[1].length
      text = heading[2].strip
      html << %(<h#{level} id="#{slug(text)}">#{inline_markdown(text)}</h#{level}>\n)
    elsif line.start_with?("> ")
      flush_paragraph.call
      close_list.call
      html << "<blockquote>#{inline_markdown(line.sub(/^>\s+/, ''))}</blockquote>\n"
    elsif line =~ /^[-*]\s+(.+)$/
      flush_paragraph.call
      if list_type != "ul"
        close_list.call
        html << "<ul>\n"
        list_type = "ul"
      end
      html << "<li>#{inline_markdown(Regexp.last_match(1))}</li>\n"
    elsif line =~ /^\d+\.\s+(.+)$/
      flush_paragraph.call
      if list_type != "ol"
        close_list.call
        html << "<ol>\n"
        list_type = "ol"
      end
      html << "<li>#{inline_markdown(Regexp.last_match(1))}</li>\n"
    else
      paragraph << line
    end

    index += 1
  end

  flush_paragraph.call
  close_list.call
  html
end

def page(title, body, depth: 1)
  prefix = depth.positive? ? "../" : ""
  <<~HTML
    <!doctype html>
    <html lang="en">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>#{CGI.escapeHTML(title)} - Olly Docs</title>
      <link rel="stylesheet" href="#{prefix}styles.css">
    </head>
    <body>
      <header class="site-header">
        <a class="brand" href="#{prefix}">Olly Docs</a>
        <nav aria-label="Primary">
          <a href="./">Guides</a>
          <a href="#{prefix}api/">API</a>
          <a href="https://github.com/gongahkia/olly">GitHub</a>
        </nav>
      </header>
      <main>
        <article class="doc-shell">
    #{body}
        </article>
      </main>
    </body>
    </html>
  HTML
end

docs = Dir.glob(File.join(docs_dir, "*.md")).sort.map do |path|
  basename = File.basename(path, ".md")
  title = title_for(path)
  html_path = File.join(docs_output_dir, "#{basename}.html")
  File.write(html_path, page(title, render_markdown(File.read(path))))
  [basename, title]
end

available = docs.to_h
index_body = +"<h1>Guides</h1>\n<p>Checked-in project docs rendered as static pages.</p>\n"
DOC_GROUPS.each do |group, basenames|
  entries = basenames.select { |basename| available.key?(basename) }
  next if entries.empty?

  index_body << "<h2>#{CGI.escapeHTML(group)}</h2>\n<ul class=\"doc-index\">\n"
  entries.each do |basename|
    index_body << %(<li><a href="#{basename}.html">#{CGI.escapeHTML(available.fetch(basename))}</a></li>\n)
  end
  index_body << "</ul>\n"
end

ungrouped = docs.map(&:first) - DOC_GROUPS.values.flatten
unless ungrouped.empty?
  index_body << "<h2>Other</h2>\n<ul class=\"doc-index\">\n"
  ungrouped.each do |basename|
    index_body << %(<li><a href="#{basename}.html">#{CGI.escapeHTML(available.fetch(basename))}</a></li>\n)
  end
  index_body << "</ul>\n"
end

File.write(File.join(docs_output_dir, "index.html"), page("Guides", index_body))
