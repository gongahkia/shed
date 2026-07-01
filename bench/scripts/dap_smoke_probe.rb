#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "open3"
require "timeout"

class DAPSmokeError < StandardError; end

class DAPClient
	def initialize(command)
		@stdin, @stdout, @stderr, @wait_thread = Open3.popen3(command)
		@buffer = +""
		@seq = 1
		@stderr_thread = Thread.new do
			@stderr.each_line { |line| warn "lldb-dap: #{line}" if ENV["ITSY_DAP_SMOKE_VERBOSE"] }
		rescue IOError
			nil
		end
	end

	def close
		@stdin.close unless @stdin.closed?
		Process.kill("TERM", @wait_thread.pid) if @wait_thread&.alive?
	rescue Errno::ESRCH, IOError
		nil
	ensure
		@stderr_thread&.kill
	end

	def request(command, arguments = nil)
		seq = @seq
		@seq += 1
		message = { seq: seq, type: "request", command: command }
		message[:arguments] = arguments if arguments
		write(message)
		seq
	end

	def wait_response(request_seq, timeout: 10)
		wait_until(timeout: timeout) do |message|
			message["type"] == "response" && message["request_seq"] == request_seq
		end
	end

	def wait_event(name, timeout: 10)
		wait_until(timeout: timeout) do |message|
			message["type"] == "event" && message["event"] == name
		end
	end

	private

	def write(message)
		payload = JSON.generate(message)
		@stdin.write("Content-Length: #{payload.bytesize}\r\n\r\n#{payload}")
		@stdin.flush
	end

	def wait_until(timeout:)
		deadline = monotonic + timeout
		loop do
			message = read_message(deadline)
			handle_server_request(message)
			return message if yield(message)
		end
	end

	def read_message(deadline)
		read_until_header(deadline)
		header, rest = @buffer.split("\r\n\r\n", 2)
		length = header[/Content-Length:\s*(\d+)/i, 1]&.to_i
		raise DAPSmokeError, "missing Content-Length in #{header.inspect}" unless length

		read_until_length(rest.bytesize, length, deadline)
		header, rest = @buffer.split("\r\n\r\n", 2)
		payload = rest.byteslice(0, length)
		@buffer = rest.byteslice(length..) || +""
		JSON.parse(payload)
	end

	def read_until_header(deadline)
		read_until(deadline) { @buffer.include?("\r\n\r\n") }
	end

	def read_until_length(current, length, deadline)
		read_until(deadline) do
			_, rest = @buffer.split("\r\n\r\n", 2)
			rest && rest.bytesize >= length
		end
	end

	def read_until(deadline)
		until yield
			remaining = deadline - monotonic
			raise DAPSmokeError, "timed out waiting for DAP message" if remaining <= 0

			ready = IO.select([@stdout], nil, nil, remaining)
			raise DAPSmokeError, "timed out waiting for DAP stdout" unless ready

			chunk = @stdout.read_nonblock(4096, exception: false)
			raise DAPSmokeError, "lldb-dap closed stdout" if chunk.nil?
			next if chunk == :wait_readable

			@buffer << chunk
		end
	end

	def handle_server_request(message)
		return unless message["type"] == "request"

		response = {
			seq: @seq,
			type: "response",
			request_seq: message["seq"],
			success: false,
			command: message["command"],
			message: "unsupported request in smoke client"
		}
		@seq += 1
		write(response)
	end

	def monotonic
		Process.clock_gettime(Process::CLOCK_MONOTONIC)
	end
end

def expand_path(value, workspace)
	value.gsub("${workspaceFolder}", workspace)
end

def resolve_adapter(command)
	return ENV["LLDB_DAP"] if ENV["LLDB_DAP"] && File.executable?(ENV["LLDB_DAP"])
	return command if command.include?("/") && File.executable?(command)

	found = `command -v #{command} 2>/dev/null`.strip
	return found unless found.empty?

	xcrun = `/usr/bin/xcrun --find #{command} 2>/dev/null`.strip
	return xcrun unless xcrun.empty?

	raise DAPSmokeError, "missing #{command}"
end

def checked_response!(response)
	raise DAPSmokeError, "#{response["command"]} failed: #{response["message"]}" unless response["success"]
end

def request_and_check(client, command, args = nil)
	seq = client.request(command, args)
	response = client.wait_response(seq)
	checked_response!(response)
	response
end

def stack_frame(client, thread_id)
	response = request_and_check(client, "stackTrace", "threadId" => thread_id)
	frames = response.dig("body", "stackFrames") || []
	raise DAPSmokeError, "empty stack trace" if frames.empty?

	frames.first
end

def variables(client, frame_id)
	scopes_response = request_and_check(client, "scopes", "frameId" => frame_id)
	scopes = scopes_response.dig("body", "scopes") || []
	scopes.flat_map do |scope|
		ref = scope["variablesReference"]
		next [] unless ref && ref.positive?

		response = request_and_check(client, "variables", "variablesReference" => ref)
		response.dig("body", "variables") || []
	end
end

workspace, config_path, source_path, break_line = ARGV
raise DAPSmokeError, "usage: dap_smoke_probe.rb WORKSPACE CONFIG SOURCE BREAK_LINE" unless break_line

config = JSON.parse(File.read(config_path))
adapter = config.fetch("adapters").find { |entry| entry["id"] == "lldb" } || config.fetch("adapters").first
launch = config.fetch("configurations").find { |entry| entry["name"] == "Debug Hello" } || config.fetch("configurations").first
program = expand_path(launch.fetch("program"), workspace)
adapter_command = resolve_adapter(adapter.fetch("command"))
client = DAPClient.new(adapter_command)

begin
	checked_response!(client.wait_response(client.request("initialize", {
		"clientID" => "itsy-dap-smoke",
		"clientName" => "Itsy DAP Smoke",
		"adapterID" => adapter.fetch("id"),
		"linesStartAt1" => true,
		"columnsStartAt1" => true,
		"pathFormat" => "path",
		"supportsRunInTerminalRequest" => false
	})))

	client.request(launch.fetch("request"), {
		"program" => program,
		"cwd" => expand_path(launch.fetch("cwd", workspace), workspace),
		"stopOnEntry" => launch.fetch("stopOnEntry", false),
		"args" => launch.fetch("args", [])
	})
	client.wait_event("initialized", timeout: 10)
	checked_response!(client.wait_response(client.request("setBreakpoints", {
		"source" => { "path" => source_path },
		"breakpoints" => [{ "line" => break_line.to_i }]
	})))
	checked_response!(client.wait_response(client.request("configurationDone")))

	stopped = client.wait_event("stopped", timeout: 15)
	thread_id = stopped.dig("body", "threadId")
	raise DAPSmokeError, "missing stopped threadId" unless thread_id

	frame = stack_frame(client, thread_id)
	raise DAPSmokeError, "breakpoint did not stop in debug-hello.swift" unless frame.dig("source", "path") == source_path

	client.request("next", "threadId" => thread_id)
	client.wait_event("stopped", timeout: 10)
	frame = stack_frame(client, thread_id)
	variable = variables(client, frame.fetch("id")).find { |entry| entry["name"] == "stepped" }
	raise DAPSmokeError, "missing stepped variable after step-over" unless variable
	raise DAPSmokeError, "unexpected stepped value: #{variable["value"].inspect}" unless variable["value"].to_s.include?("42")

	puts "dap smoke ok: breakpoint line #{break_line}, step-over, stepped=#{variable["value"]}"
ensure
	begin
		client.request("disconnect", "terminateDebuggee" => true)
	rescue StandardError
		nil
	end
	client.close
end
