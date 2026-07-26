const net = require("net");
const { spawn } = require("child_process");

const [entry] = process.argv.slice(2);
if (!entry) {
	process.stderr.write("missing js-debug entrypoint\n");
	process.exit(2);
}

class DAPFramer {
	constructor(onMessage) {
		this.onMessage = onMessage;
		this.buffer = Buffer.alloc(0);
	}

	push(data) {
		this.buffer = Buffer.concat([this.buffer, data]);
		while (true) {
			const end = this.buffer.indexOf("\r\n\r\n");
			if (end < 0) return;
			const header = this.buffer.subarray(0, end).toString("ascii");
			const match = header.match(/^Content-Length:\s*(\d+)\s*$/im);
			if (!match) throw new Error("missing DAP Content-Length");
			const length = Number(match[1]);
			const frameEnd = end + 4 + length;
			if (this.buffer.length < frameEnd) return;
			const frame = this.buffer.subarray(0, frameEnd);
			const payload = frame.subarray(end + 4);
			this.buffer = this.buffer.subarray(frameEnd);
			this.onMessage(JSON.parse(payload.toString("utf8")), frame);
		}
	}
}

const frame = message => {
	const payload = Buffer.from(JSON.stringify(message));
	return Buffer.concat([Buffer.from(`Content-Length: ${payload.length}\r\n\r\n`), payload]);
};

let bridgeSequence = 1_000_000;
let activeSocket;
let primaryInitialize;
const cachedRequests = [];
const pendingPrimaryFrames = [];
let listeningPort;
let childSocket;
let childReady = false;

const fail = error => {
	process.stderr.write(`${error instanceof Error ? error.stack || error.message : error}\n`);
	if (childSocket) childSocket.destroy();
	debugServer.kill();
	process.exitCode = 1;
};

const debugServer = spawn(process.execPath, [entry, "0"], { stdio: ["ignore", "pipe", "pipe"] });
debugServer.on("error", fail);
debugServer.on("exit", code => {
	if (code !== 0) process.exitCode = code || 1;
});
debugServer.stderr.on("data", data => process.stderr.write(data));
debugServer.stdout.on("data", data => {
	const match = data.toString().match(/Debug server listening at .*:([0-9]+)/);
	if (!match || listeningPort) return;
	listeningPort = Number(match[1]);
	connectPrimary();
});

const primaryInput = new DAPFramer((message, raw) => {
	if (message.type === "request") {
		if (message.command === "initialize") primaryInitialize = message.arguments;
		if (["setBreakpoints", "setExceptionBreakpoints"].includes(message.command)) {
			cachedRequests.push({ command: message.command, arguments: message.arguments });
		}
	}
	if (!activeSocket) {
		pendingPrimaryFrames.push(raw);
		return;
	}
	activeSocket.write(raw);
});
process.stdin.on("data", data => {
	try {
		primaryInput.push(data);
	} catch (error) {
		fail(error);
	}
});
process.stdin.on("end", () => debugServer.kill());

function connect(options) {
	return new Promise((resolve, reject) => {
		const socket = net.connect(options);
		socket.once("connect", () => resolve(socket));
		socket.once("error", reject);
	});
}

function respond(socket, request, success, message) {
	socket.write(frame({
		seq: bridgeSequence++,
		type: "response",
		request_seq: request.seq,
		success,
		command: request.command,
		...(message ? { message } : {}),
	}));
}

function connectPrimary() {
	connect({ host: "::1", port: listeningPort }).then(socket => {
		activeSocket = socket;
		for (const pending of pendingPrimaryFrames.splice(0)) socket.write(pending);
		const output = new DAPFramer((message, raw) => {
			if (message.type === "request" && message.command === "startDebugging") {
				bootstrapTarget(message).catch(error => {
					respond(socket, message, false, String(error));
					fail(error);
				});
				return;
			}
			process.stdout.write(raw);
		});
		socket.on("data", data => {
			try {
				output.push(data);
			} catch (error) {
				fail(error);
			}
		});
		socket.on("error", fail);
		socket.on("close", () => debugServer.kill());
	}).catch(fail);
}

async function bootstrapTarget(request) {
	if (childSocket) throw new Error("js-debug child-process auto-attach is unsupported");
	const configuration = request.arguments?.configuration;
	if (!configuration || !primaryInitialize) throw new Error("js-debug target request is missing DAP configuration");
	childSocket = await connect({ host: "::1", port: listeningPort });
	const internalSequences = new Set();
	let sequence = 1;
	const send = (command, arguments) => {
		const requestSequence = sequence++;
		internalSequences.add(requestSequence);
		childSocket.write(frame({ seq: requestSequence, type: "request", command, ...(arguments ? { arguments } : {}) }));
		return requestSequence;
	};
	const initializeSequence = send("initialize", primaryInitialize);
	const output = new DAPFramer((message, raw) => {
		if (message.type === "response" && internalSequences.delete(message.request_seq)) {
			if (message.request_seq === initializeSequence) {
				send("launch", configuration);
				for (const cached of cachedRequests) send(cached.command, cached.arguments);
				send("configurationDone");
				childReady = true;
				respond(activeSocket, request, true);
			}
			return;
		}
		if (!childReady && message.type === "event" && message.event === "initialized") return;
		if (childReady) process.stdout.write(raw);
	});
	childSocket.on("data", data => {
		try {
			output.push(data);
		} catch (error) {
			fail(error);
		}
	});
	childSocket.on("error", fail);
	childSocket.on("close", () => debugServer.kill());
	activeSocket = childSocket;
}
