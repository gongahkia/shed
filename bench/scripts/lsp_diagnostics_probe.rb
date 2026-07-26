#!/usr/bin/env ruby
# frozen_string_literal: true

require "fileutils"
require "json"
require "open3"
require "shellwords"
require "tmpdir"
require "uri"

class LSPProbeError < StandardError; end

class LSPConnection
	def initialize(command, cwd:, workspace_folders:)
		@stdin, @stdout, @stderr, @wait_thr = Open3.popen3(*command, chdir: cwd)
		@workspace_folders = workspace_folders
		@server_label = command.join(" ")
		@buffer = +"".b
		@next_id = 1
		@stderr_log = +""
		@stderr_thread = Thread.new do
			@stderr_log << (@stderr.read || "")
		rescue IOError
			nil
		end
	end

	attr_reader :stderr_log

	def request(method, params)
		id = @next_id
		@next_id += 1
		write("jsonrpc" => "2.0", "id" => id, "method" => method, "params" => params)
		id
	end

	def notify(method, params = nil)
		message = { "jsonrpc" => "2.0", "method" => method }
		message["params"] = params unless params.nil?
		write(message)
	end

	def wait_for_response(id, deadline)
		loop do
			message, = read_message(deadline)
			handle_server_request(message) && next
			return message if message["id"] == id
		end
	end

	def wait_for_notification(method, deadline)
		loop do
			message, received_at = read_message(deadline)
			handle_server_request(message) && next
			return [message, received_at] if message["method"] == method
		end
	end

	def close
		@stdin.close unless @stdin.closed?
	rescue IOError
		nil
	ensure
		begin
			Process.kill("TERM", @wait_thr.pid) if @wait_thr.alive?
		rescue Errno::ESRCH
			nil
		end
		@stderr_thread.join(0.2)
	end

	private

	def write(message)
		payload = JSON.generate(message)
		@stdin.write("Content-Length: #{payload.bytesize}\r\n\r\n")
		@stdin.write(payload)
		@stdin.flush
	end

	def read_message(deadline)
		loop do
			begin
				if (payload = next_payload)
					return [JSON.parse(payload), monotonic]
				end
				remaining = deadline - monotonic
				raise LSPProbeError, "timed out waiting for #{@server_label}" if remaining <= 0
				ready = IO.select([@stdout], nil, nil, remaining)
				raise LSPProbeError, "timed out waiting for #{@server_label}" if ready.nil?
				@buffer << @stdout.readpartial(16 * 1024)
			rescue EOFError
				@stderr_thread.join(0.2)
				detail = @stderr_log.lines.first(6).map(&:strip).reject(&:empty?).join(" ")
				message = "#{@server_label} exited before expected LSP message"
				message = "#{message}: #{detail}" unless detail.empty?
				raise LSPProbeError, message
			end
		end
	end

	def next_payload
		header_end = @buffer.index("\r\n\r\n")
		return nil unless header_end
		header = @buffer.byteslice(0...header_end)
		length = header.lines.find { |line| line.downcase.start_with?("content-length:") }&.split(":", 2)&.last&.strip&.to_i
		raise LSPProbeError, "missing Content-Length header" unless length
		body_start = header_end + 4
		body_end = body_start + length
		return nil if @buffer.bytesize < body_end
		payload = @buffer.byteslice(body_start, length)
		@buffer = @buffer.byteslice(body_end..-1) || +"".b
		payload
	end

	def handle_server_request(message)
		return false unless message.key?("id") && message.key?("method")
		result = case message["method"]
		when "workspace/configuration"
			Array(message.dig("params", "items")).map { {} }
		when "workspace/workspaceFolders"
			@workspace_folders
		else
			nil
		end
		write("jsonrpc" => "2.0", "id" => message["id"], "result" => result)
		true
	end
end

def monotonic
	Process.clock_gettime(Process::CLOCK_MONOTONIC)
end

def file_uri(path)
	"file://#{URI::DEFAULT_PARSER.escape(File.expand_path(path))}"
end

def file_uri_path(uri)
	parsed = URI.parse(uri.to_s)
	return nil unless parsed.scheme == "file"
	URI::DEFAULT_PARSER.unescape(parsed.path)
rescue URI::InvalidURIError
	nil
end

def same_file_uri?(lhs, rhs)
	return true if lhs == rhs
	lhs_path = file_uri_path(lhs)
	rhs_path = file_uri_path(rhs)
	return false unless lhs_path && rhs_path
	File.expand_path(lhs_path) == File.expand_path(rhs_path)
end

def write_probe_package(root, line_count)
	source_dir = File.join(root, "Sources", "ItsyLSPProbe")
	FileUtils.mkdir_p(source_dir)
	File.write(File.join(root, "Package.swift"), <<~SWIFT)
		// swift-tools-version: 5.9
		import PackageDescription
		let package = Package(name: "ItsyLSPProbe", targets: [.executableTarget(name: "ItsyLSPProbe")])
	SWIFT
	source_path = File.join(source_dir, "main.swift")
	File.open(source_path, "w") do |file|
		[line_count - 1, 0].max.times do |index|
			file.puts("// sourcekit-lsp probe line #{format("%06d", index + 1)}")
		end
		file.puts('let diagnosticProbeValue: Int = "not an int"')
	end
	source_path
end

def run_probe(root, source_path, language_id, command, limit_ms, generated_line_count: nil)
	root_uri = file_uri(root)
	source_uri = file_uri(source_path)
	workspace_folders = [{ "uri" => root_uri, "name" => File.basename(root) }]
	connection = LSPConnection.new(command, cwd: root, workspace_folders: workspace_folders)
	begin
		initialize_id = connection.request("initialize", {
			"processId" => Process.pid,
			"rootPath" => root,
			"rootUri" => root_uri,
			"workspaceFolders" => workspace_folders,
			"clientInfo" => { "name" => ENV.fetch("ITSY_LSP_CLIENT_NAME", "itsy-regression"), "version" => "1" },
			"capabilities" => {
				"textDocument" => { "publishDiagnostics" => { "relatedInformation" => true, "versionSupport" => true } },
				"workspace" => { "configuration" => true, "workspaceFolders" => true }
			}
		})
		connection.wait_for_response(initialize_id, monotonic + 15)
		connection.notify("initialized", {})
		text = File.read(source_path)
		did_open_at = monotonic
		connection.notify("textDocument/didOpen", {
			"textDocument" => {
				"uri" => source_uri,
				"languageId" => language_id,
				"version" => 1,
				"text" => text
			}
		})
		deadline = did_open_at + (limit_ms / 1000.0)
		message = nil
		received_at = nil
		loop do
			message, received_at = connection.wait_for_notification("textDocument/publishDiagnostics", deadline)
			break if same_file_uri?(message.dig("params", "uri"), source_uri)
		end
		latency_ms = (received_at - did_open_at) * 1000.0
		result = {
			"language_id" => language_id,
			"lsp_command" => command.join(" "),
			"lsp_didopen_to_diagnostics_ms" => latency_ms,
			"lsp_diagnostics_count" => Array(message.dig("params", "diagnostics")).length
		}
		result["lsp_probe_lines"] = generated_line_count if generated_line_count
		puts JSON.pretty_generate(result)
	ensure
		begin
			shutdown_id = connection.request("shutdown", nil)
			connection.wait_for_response(shutdown_id, monotonic + 2)
			connection.notify("exit")
		rescue StandardError
			nil
		end
		connection.close
	end
end

limit_ms = Float(ENV.fetch("ITSY_LSP_DIAGNOSTICS_LIMIT_MS", "5000"))
command = Shellwords.split(ENV.fetch("ITSY_LSP_COMMAND", ENV.fetch("SOURCEKIT_LSP", "sourcekit-lsp")))
raise LSPProbeError, "LSP command resolved to an empty command" if command.empty?

if ENV["ITSY_LSP_FILE"]
	source_path = File.expand_path(ENV.fetch("ITSY_LSP_FILE"))
	root = File.expand_path(ENV.fetch("ITSY_LSP_ROOT", File.dirname(source_path)))
	language_id = ENV.fetch("ITSY_LSP_LANGUAGE_ID")
	run_probe(root, source_path, language_id, command, limit_ms)
else
	line_count = Integer(ENV.fetch("ITSY_LSP_DIAGNOSTICS_PROBE_LINES", "100000"))
	raise LSPProbeError, "ITSY_LSP_DIAGNOSTICS_PROBE_LINES must be >0" unless line_count.positive?
	Dir.mktmpdir("itsy-lsp-diagnostics-") do |root|
		source_path = write_probe_package(root, line_count)
		run_probe(root, source_path, "swift", command, limit_ms, generated_line_count: line_count)
	end
end
