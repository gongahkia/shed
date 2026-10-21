//! Deliberately small MCP stdio binding. The experiment backend owns semantics;
//! this module owns framing, lifecycle and JSON-RPC error boundaries.

use super::{API_VERSION, MAX_MESSAGE_BYTES, files::Workspace, tools};
use serde_json::{Value, json};
use std::io::{self, BufRead, Read, Write};

const PROTOCOL_VERSION: &str = "2025-11-25";

#[derive(Default)]
struct Session {
    initialized: bool,
    ready: bool,
}

fn error(id: Value, code: i32, message: &str) -> Value {
    let mut response = json!({"jsonrpc":"2.0","error":{"code":code,"message":message}});
    response["id"] = id;
    response
}

fn success(id: Value, result: Value) -> Value {
    let mut response = json!({"jsonrpc":"2.0"});
    response["id"] = id;
    response["result"] = result;
    response
}

pub(super) fn serve(
    workspace: &Workspace,
    mut input: impl BufRead,
    mut output: impl Write,
) -> io::Result<()> {
    let mut session = Session::default();
    loop {
        let mut bytes = vec![];
        let read = input
            .by_ref()
            .take(u64::try_from(MAX_MESSAGE_BYTES + 1).expect("bounded"))
            .read_until(b'\n', &mut bytes)?;
        if read == 0 {
            return Ok(());
        }
        if bytes.len() > MAX_MESSAGE_BYTES {
            emit(
                &mut output,
                &error(Value::Null, -32700, "message exceeds 16 MiB"),
            )?;
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "MCP input message exceeded the limit",
            ));
        }
        let response = match serde_json::from_slice::<Value>(&bytes) {
            Ok(value) => session.handle(workspace, &value),
            Err(_) => Some(error(Value::Null, -32700, "invalid JSON")),
        };
        if let Some(response) = response {
            emit(&mut output, &response)?;
        }
    }
}

fn emit(output: &mut impl Write, value: &Value) -> io::Result<()> {
    serde_json::to_writer(&mut *output, value)?;
    output.write_all(b"\n")?;
    output.flush()
}

impl Session {
    fn handle(&mut self, workspace: &Workspace, request: &Value) -> Option<Value> {
        let Some(object) = request.as_object() else {
            return Some(error(
                Value::Null,
                -32600,
                "expected one JSON-RPC request object; batches are unsupported",
            ));
        };
        let id = object.get("id").cloned();
        if id
            .as_ref()
            .is_some_and(|id| !id.is_string() && !id.is_i64() && !id.is_u64())
        {
            return Some(error(
                Value::Null,
                -32600,
                "request ID must be a string or integer",
            ));
        }
        let valid_id = id.clone().unwrap_or(Value::Null);
        let Some(method) = object.get("method").and_then(Value::as_str) else {
            return Some(error(valid_id, -32600, "request method must be a string"));
        };
        if object.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
            return Some(error(valid_id, -32600, "expected jsonrpc 2.0"));
        }
        if id.is_none() {
            if method == "notifications/initialized" && self.initialized {
                self.ready = true;
            }
            // Valid notifications never elicit responses, including unknown ones.
            return None;
        }
        let params = object.get("params").cloned().unwrap_or_else(|| json!({}));
        if !params.is_object() {
            return Some(error(valid_id, -32602, "params must be an object"));
        }
        let result = match method {
            "ping" => success(valid_id, json!({})),
            "initialize" => self.initialize(valid_id, &params),
            "tools/list" | "tools/call" if !self.ready => error(
                valid_id,
                -32600,
                "complete initialize and notifications/initialized before using tools",
            ),
            "tools/list" => {
                if params.get("cursor").is_some() {
                    error(valid_id, -32602, "this static tool catalog has no cursor")
                } else {
                    success(valid_id, json!({"tools":tools::catalog()}))
                }
            }
            "tools/call" => {
                let Some(name) = params["name"].as_str() else {
                    return Some(error(valid_id, -32602, "tools/call requires a tool name"));
                };
                if !tools::catalog().iter().any(|tool| tool["name"] == name) {
                    return Some(error(valid_id, -32602, "unknown tool"));
                }
                let arguments = params
                    .get("arguments")
                    .cloned()
                    .unwrap_or_else(|| json!({}));
                let (envelope, is_error) = match tools::call(workspace, name, arguments) {
                    Ok(result) => (
                        json!({"api_version":API_VERSION,"ok":true,"result":result}),
                        false,
                    ),
                    Err(tool_error) if tool_error.code == "arguments" => {
                        return Some(error(valid_id, -32602, &tool_error.message));
                    }
                    Err(tool_error) => (
                        json!({"api_version":API_VERSION,"ok":false,"error":tool_error}),
                        true,
                    ),
                };
                success(
                    valid_id,
                    json!({"content":[{"type":"text","text":serde_json::to_string(&envelope).expect("tool envelope JSON")}],"structuredContent":envelope,"isError":is_error}),
                )
            }
            _ => error(valid_id, -32601, "method not found"),
        };
        Some(result)
    }
}

impl Session {
    fn initialize(&mut self, id: Value, params: &Value) -> Value {
        if self.initialized {
            return error(id, -32600, "session is already initialized");
        }
        if !params["protocolVersion"].is_string()
            || !params["capabilities"].is_object()
            || !params["clientInfo"]["name"].is_string()
            || !params["clientInfo"]["version"].is_string()
        {
            return error(
                id,
                -32602,
                "initialize requires protocolVersion, capabilities and clientInfo name/version",
            );
        }
        self.initialized = true;
        success(
            id,
            json!({"protocolVersion":PROTOCOL_VERSION,
                    "serverInfo":{"name":"chiyoda","version":chiyoda_core::RUNTIME_VERSION},
                    "capabilities":{"tools":{"listChanged":false}},
                    "instructions":"Discover capabilities, inspect or generate a baseline, plan independent variants, run with the planned input hash, then query evidence and verify results. All artifact paths are relative to the configured workspace. Tools are synchronous and bounded; no language-model service is called by Chiyoda."}),
        )
    }
}
