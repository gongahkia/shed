use serde_json::{Value, json};
use std::{
    fs,
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, ChildStdout, Command, Stdio},
    sync::atomic::{AtomicU64, Ordering},
};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "chiyoda-agent-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        fs::write(
            path.join("scenario.json"),
            include_str!("../../chiyoda-core/tests/fixtures/json-free-walk.json"),
        )
        .unwrap();
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn read(path: &Path) -> Value {
    serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
}
fn write(path: &Path, value: &Value) {
    fs::write(path, serde_json::to_vec_pretty(value).unwrap()).unwrap();
}
fn call(directory: &Directory, tool: &str, input: &Value) -> Value {
    let mut child = Command::new(env!("CARGO_BIN_EXE_chiyoda"))
        .args(["agent", "call", tool, "--input", "-", "--workspace"])
        .arg(&directory.0)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child
        .stdin
        .take()
        .unwrap()
        .write_all(serde_json::to_string(input).unwrap().as_bytes())
        .unwrap();
    let output = child.wait_with_output().unwrap();
    let envelope: Value = serde_json::from_slice(&output.stdout).unwrap_or_else(|_| {
        panic!(
            "stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        )
    });
    assert_eq!(output.status.success(), envelope["ok"].as_bool().unwrap());
    envelope
}
fn ok(directory: &Directory, tool: &str, input: &Value) -> Value {
    let envelope = call(directory, tool, input);
    assert_eq!(envelope["ok"], true, "{envelope:#}");
    envelope["result"].clone()
}
fn experiment() -> Value {
    json!({"name":"One intervention","scenario":"scenario.json","variants":[{"id":"candidate","changes":[{"kind":"set_exit_capacity","id":"out","capacity_per_s":0.2}]}]})
}
fn run(directory: &Directory, input: &Value) -> Value {
    let plan = ok(directory, "chiyoda_plan", input);
    ok(
        directory,
        "chiyoda_run",
        &json!({"experiment":input,"expected_input_hash":plan["input_hash"],"output":"result"}),
    )
}

#[test]
fn tool_discovery_and_schemas_are_generated_and_strict() {
    let directory = Directory::new();
    let output = Command::new(env!("CARGO_BIN_EXE_chiyoda"))
        .args(["agent", "tools"])
        .output()
        .unwrap();
    assert!(output.status.success());
    let catalog: Value = serde_json::from_slice(&output.stdout).unwrap();
    let tools = catalog["tools"].as_array().unwrap();
    assert_eq!(tools.len(), 8);
    assert!(
        tools
            .iter()
            .all(|tool| tool["inputSchema"]["type"] == "object"
                && tool["inputSchema"]["additionalProperties"] == false)
    );
    assert_eq!(
        ok(&directory, "chiyoda_schema", &json!({"kind":"scenario"}))["properties"]["schema_version"]
            ["const"],
        "1"
    );
    assert_eq!(
        call(&directory, "chiyoda_capabilities", &json!({"typo":1}))["error"]["code"],
        "arguments"
    );
    assert_eq!(
        call(&directory, "no_such_tool", &json!({}))["error"]["code"],
        "unknown_tool"
    );
}

#[test]
fn full_cli_loop_retains_evidence_and_survives_moving_the_archive_and_removing_source() {
    let directory = Directory::new();
    let original = fs::read(directory.0.join("scenario.json")).unwrap();
    let inspected = ok(
        &directory,
        "chiyoda_inspect",
        &json!({"scenario":"scenario.json"}),
    );
    assert_eq!(inspected["people"], 1);
    assert_eq!(inspected["issues"], json!([]));
    let report = run(&directory, &experiment());
    assert_eq!(report["comparisons"][0]["matched_people"], 1);
    assert!(report["comparisons"][0].get("agents").is_none());
    let query = ok(
        &directory,
        "chiyoda_query",
        &json!({"experiment":"result","view":"agents","arm":"candidate","limit":1}),
    );
    assert_eq!(query["total"], 1);
    let source = &query["items"][0]["source"];
    let evidence = read(
        &directory
            .0
            .join("result")
            .join(source["artifact"].as_str().unwrap()),
    );
    assert_eq!(
        evidence
            .pointer(source["pointer"].as_str().unwrap())
            .unwrap()["agent"],
        "person:0"
    );
    let trace = ok(
        &directory,
        "chiyoda_query",
        &json!({"experiment":"result","view":"trace","arm":"baseline","step":0}),
    );
    assert_eq!(trace["items"][0]["value"]["id"], "person:0");
    assert_eq!(trace["replay"]["args"][3], "0");
    assert_eq!(
        call(
            &directory,
            "chiyoda_query",
            &json!({"experiment":"result","view":"trace","arm":"baseline","step":999_999})
        )["error"]["code"],
        "step_not_recorded"
    );
    assert_eq!(
        original,
        fs::read(directory.0.join("scenario.json")).unwrap()
    );
    fs::remove_file(directory.0.join("scenario.json")).unwrap();
    fs::rename(
        directory.0.join("result"),
        directory.0.join("portable experiment"),
    )
    .unwrap();
    assert_eq!(
        ok(
            &directory,
            "chiyoda_verify",
            &json!({"experiment":"portable experiment","replay":true})
        )["verified"],
        true
    );
}

#[test]
fn invalid_and_stale_plans_never_execute_or_create_output() {
    let directory = Directory::new();
    let mut input = experiment();
    input["variants"][0]["changes"][0]["id"] = "missing".into();
    let plan = ok(&directory, "chiyoda_plan", &input);
    assert_eq!(plan["runnable"], false);
    let result = call(
        &directory,
        "chiyoda_run",
        &json!({"experiment":input,"expected_input_hash":plan["input_hash"],"output":"bad"}),
    );
    assert_eq!(result["error"]["code"], "invalid_plan");
    assert_eq!(
        result["error"]["details"]["arms"][1]["issues"][0]["code"],
        "unknown_id"
    );
    assert!(!directory.0.join("bad").exists());
    let input = experiment();
    let plan = ok(&directory, "chiyoda_plan", &input);
    let mut source = read(&directory.0.join("scenario.json"));
    source["scenario"]["seed"] = 2.into();
    write(&directory.0.join("scenario.json"), &source);
    assert_eq!(
        call(
            &directory,
            "chiyoda_run",
            &json!({"experiment":input,"expected_input_hash":plan["input_hash"],"output":"stale"})
        )["error"]["code"],
        "stale_plan"
    );
    assert!(!directory.0.join("stale").exists());
}

#[test]
fn pagination_preserves_denominators_and_changed_population_disables_pairs() {
    let directory = Directory::new();
    let mut source = read(&directory.0.join("scenario.json"));
    source["scenario"]["agents"][0]["count"] = 3.into();
    write(&directory.0.join("scenario.json"), &source);
    let mut input = experiment();
    input["variants"][0]["changes"] = json!([{"kind":"set_group_count","id":"person","count":4}]);
    run(&directory, &input);
    let query = ok(
        &directory,
        "chiyoda_query",
        &json!({"experiment":"result","view":"agents","arm":"baseline","limit":1,"offset":1}),
    );
    assert_eq!(query["total"], 3);
    assert_eq!(query["next_offset"], 2);
    assert_eq!(query["items"][0]["value"]["agent"], "person:1");
    let pairs = ok(
        &directory,
        "chiyoda_query",
        &json!({"experiment":"result","view":"comparisons","arm":"candidate"}),
    );
    assert_eq!(pairs["same_population_inputs"], false);
    assert_eq!(pairs["total"], 0);
    for view in ["events", "low_progress", "hotspots", "resources", "changes"] {
        ok(
            &directory,
            "chiyoda_query",
            &json!({"experiment":"result","view":view,"arm":"baseline","limit":1}),
        );
    }
    assert_eq!(
        call(
            &directory,
            "chiyoda_query",
            &json!({"experiment":"result","view":"agents","arm":"baseline","limit":201})
        )["error"]["code"],
        "arguments"
    );
    assert_eq!(
        call(
            &directory,
            "chiyoda_query",
            &json!({"experiment":"result","view":"agents","arm":"baseline","agent":"missing"})
        )["error"]["code"],
        "unknown_id"
    );
}

#[test]
fn artifacts_and_rehashed_false_conclusions_are_independently_checked() {
    let directory = Directory::new();
    run(&directory, &experiment());
    let path = directory.0.join("result/report.json");
    let mut report: chiyoda_core::experiment::ExperimentReport =
        serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    report.comparisons[0].agents[0].elapsed_difference_s = Some(-500.0);
    report.report_hash.clear();
    report.report_hash = chiyoda_core::bundle::canonical_hash(&report);
    write(&path, &serde_json::to_value(report).unwrap());
    assert_eq!(
        call(
            &directory,
            "chiyoda_verify",
            &json!({"experiment":"result"})
        )["error"]["code"],
        "integrity"
    );
}

#[test]
fn workspace_paths_and_existing_outputs_are_preserved() {
    let directory = Directory::new();
    assert_eq!(
        call(
            &directory,
            "chiyoda_inspect",
            &json!({"scenario":"../scenario.json"})
        )["error"]["code"],
        "path"
    );
    assert_eq!(
        call(
            &directory,
            "chiyoda_inspect",
            &json!({"scenario":directory.0.join("scenario.json")})
        )["error"]["code"],
        "path"
    );
    run(&directory, &experiment());
    let original = fs::read(directory.0.join("result/report.json")).unwrap();
    let plan = ok(&directory, "chiyoda_plan", &experiment());
    assert_eq!(
        call(
            &directory,
            "chiyoda_run",
            &json!({"experiment":experiment(),"expected_input_hash":plan["input_hash"],"output":"result"})
        )["error"]["code"],
        "destination_exists"
    );
    assert_eq!(
        original,
        fs::read(directory.0.join("result/report.json")).unwrap()
    );
    #[cfg(unix)]
    {
        let outside = Directory::new();
        std::os::unix::fs::symlink(&outside.0, directory.0.join("outside")).unwrap();
        assert_eq!(
            call(
                &directory,
                "chiyoda_inspect",
                &json!({"scenario":"outside/scenario.json"})
            )["error"]["code"],
            "path"
        );
    }
}

#[test]
fn oversized_tool_results_are_rejected_with_a_small_structured_error() {
    let directory = Directory::new();
    let mut source = read(&directory.0.join("scenario.json"));
    source["scenario"]["name"] = "x".repeat(1024 * 1024).into();
    write(&directory.0.join("scenario.json"), &source);
    let result = call(
        &directory,
        "chiyoda_inspect",
        &json!({"scenario":"scenario.json"}),
    );
    assert_eq!(result["error"]["code"], "resource_limit");
    assert!(serde_json::to_vec(&result).unwrap().len() < 1024);
}

#[test]
fn agents_generate_reproducible_wfc_baselines_with_provenance() {
    let directory = Directory::new();
    let first = ok(
        &directory,
        "chiyoda_generate",
        &json!({"seed":73,"output":"generated-a"}),
    );
    let second = ok(
        &directory,
        "chiyoda_generate",
        &json!({"seed":73,"output":"generated-b"}),
    );
    assert_eq!(first["manifest_hash"], second["manifest_hash"]);
    assert_eq!(
        read(&directory.0.join("generated-a/scenario.json")),
        read(&directory.0.join("generated-b/scenario.json"))
    );
    assert!(first["attempts"].as_u64().unwrap() > 0);
    assert_eq!(
        ok(
            &directory,
            "chiyoda_inspect",
            &json!({"scenario":"generated-a/scenario.json"})
        )["issues"],
        json!([])
    );
}

struct Rpc {
    child: Child,
    input: Option<ChildStdin>,
    output: BufReader<ChildStdout>,
}
impl Rpc {
    fn new(directory: &Directory) -> Self {
        let mut child = Command::new(env!("CARGO_BIN_EXE_chiyoda"))
            .args(["agent", "serve", "--workspace"])
            .arg(&directory.0)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let input = child.stdin.take();
        let output = BufReader::new(child.stdout.take().unwrap());
        Self {
            child,
            input,
            output,
        }
    }
    fn send(&mut self, value: &Value) {
        writeln!(self.input.as_mut().unwrap(), "{value}").unwrap();
        self.input.as_mut().unwrap().flush().unwrap();
    }
    fn request(&mut self, value: &Value) -> Value {
        self.send(value);
        let mut line = String::new();
        self.output.read_line(&mut line).unwrap();
        serde_json::from_str(&line).unwrap()
    }
    fn initialize(&mut self) {
        assert_eq!(self.request(&json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}))["result"]["protocolVersion"],"2025-11-25");
        self.send(&json!({"jsonrpc":"2.0","method":"notifications/initialized"}));
    }
    fn tool(&mut self, name: &str, args: &Value) -> Value {
        let response=self.request(&json!({"jsonrpc":"2.0","id":"tool","method":"tools/call","params":{"name":name,"arguments":args}}));
        assert_eq!(response["result"]["isError"], false, "{response:#}");
        assert_eq!(
            serde_json::from_str::<Value>(
                response["result"]["content"][0]["text"].as_str().unwrap()
            )
            .unwrap(),
            response["result"]["structuredContent"]
        );
        response["result"]["structuredContent"]["result"].clone()
    }
}
impl Drop for Rpc {
    fn drop(&mut self) {
        self.input.take();
        self.child.wait().unwrap();
    }
}

#[test]
fn mcp_negotiates_discovers_and_completes_a_real_agent_experiment_loop() {
    let directory = Directory::new();
    let mut rpc = Rpc::new(&directory);
    assert_eq!(
        rpc.request(&json!({"jsonrpc":"2.0","id":"probe","method":"server/discover"}))["error"]["code"],
        -32601
    );
    assert_eq!(
        rpc.request(&json!({"jsonrpc":"2.0","id":0,"method":"tools/list"}))["error"]["code"],
        -32600
    );
    rpc.initialize();
    assert_eq!(
        rpc.request(&json!({"jsonrpc":"2.0","id":2,"method":"tools/list"}))["result"]["tools"]
            .as_array()
            .unwrap()
            .len(),
        8
    );
    rpc.tool("chiyoda_inspect", &json!({"scenario":"scenario.json"}));
    let plan = rpc.tool("chiyoda_plan", &experiment());
    rpc.tool("chiyoda_run",&json!({"experiment":experiment(),"expected_input_hash":plan["input_hash"],"output":"mcp-result"}));
    let pairs=rpc.tool("chiyoda_query",&json!({"experiment":"mcp-result","view":"comparisons","arm":"candidate","agent":"person:0"}));
    assert_eq!(pairs["total"], 1);
    assert_eq!(
        rpc.tool(
            "chiyoda_verify",
            &json!({"experiment":"mcp-result","replay":true})
        )["verified"],
        true
    );
}

#[test]
fn mcp_keeps_protocol_errors_separate_from_domain_errors_and_notifications() {
    let directory = Directory::new();
    let mut rpc = Rpc::new(&directory);
    rpc.initialize();
    assert_eq!(rpc.request(&json!([]))["error"]["code"], -32600);
    assert_eq!(
        rpc.request(&json!({"jsonrpc":"2.0","id":null,"method":"ping"}))["error"]["code"],
        -32600
    );
    rpc.send(
        &json!({"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"old"}}),
    );
    assert_eq!(
        rpc.request(&json!({"jsonrpc":"2.0","id":3,"method":"ping"}))["id"],
        3
    );
    assert_eq!(
        rpc.request(&json!({"jsonrpc":"2.0","id":4,"method":"unsupported"}))["error"]["code"],
        -32601
    );
    assert_eq!(rpc.request(&json!({"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"chiyoda_inspect","arguments":{"typo":true}}}))["error"]["code"],-32602);
    let domain=rpc.request(&json!({"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"chiyoda_inspect","arguments":{"scenario":"missing.json"}}}));
    assert_eq!(domain["result"]["isError"], true);
    assert_eq!(
        domain["result"]["structuredContent"]["error"]["code"],
        "artifact_io"
    );
    writeln!(rpc.input.as_mut().unwrap(), "not JSON").unwrap();
    let mut line = String::new();
    rpc.output.read_line(&mut line).unwrap();
    assert_eq!(
        serde_json::from_str::<Value>(&line).unwrap()["error"]["code"],
        -32700
    );
}
