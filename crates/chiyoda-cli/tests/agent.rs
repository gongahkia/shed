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
    assert_eq!(tools.len(), 13);
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
        13
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

#[test]
fn annotated_replay_tool_and_mcp_image_share_exact_source_and_pixel_hashes() {
    use base64::{Engine, engine::general_purpose::STANDARD};
    use sha2::{Digest, Sha256};
    let directory = Directory::new();
    run(&directory, &experiment());
    let source_path = directory.0.join("result/arms/baseline/run.json");
    let before = fs::read(&source_path).unwrap();
    let source: Value = serde_json::from_slice(&before).unwrap();
    let agent = source["trace"][0]["agents"][0]["id"].as_str().unwrap();
    let step = source["trace"][2]["step"].as_u64().unwrap();
    let mut input = json!({"bundle":"result/arms/baseline/run.json","output":"evidence/person.png","request":{"step":step,"selection":{"kind":"agent","id":agent}}});
    let receipt = ok(&directory, "chiyoda_render_evidence", &input);
    assert_eq!(receipt["recorded_step"], step);
    assert_eq!(receipt["inspection"]["person"]["snapshot"]["id"], agent);
    let pixels = fs::read(directory.0.join("evidence/person.png")).unwrap();
    assert_eq!(
        receipt["image"]["sha256"],
        format!("{:x}", Sha256::digest(&pixels))
    );
    let metadata = read(&directory.0.join("evidence/person.png.json"));
    assert_eq!(metadata["image_sha256"], receipt["image"]["sha256"]);
    let pointer = metadata["inspection"]["person"]["snapshot_pointer"]
        .as_str()
        .unwrap();
    assert_eq!(
        source.pointer(pointer).unwrap(),
        &metadata["inspection"]["person"]["snapshot"]
    );
    assert_eq!(
        call(&directory, "chiyoda_render_evidence", &input)["ok"],
        false
    );
    input["output"] = "evidence/mcp.png".into();
    let mut rpc = Rpc::new(&directory);
    rpc.initialize();
    let response=rpc.request(&json!({"jsonrpc":"2.0","id":"image","method":"tools/call","params":{"name":"chiyoda_render_evidence","arguments":input}}));
    assert_eq!(response["result"]["isError"], false, "{response}");
    let image = &response["result"]["content"][1];
    assert_eq!(image["type"], "image");
    assert_eq!(image["mimeType"], "image/png");
    let returned = STANDARD.decode(image["data"].as_str().unwrap()).unwrap();
    assert_eq!(
        returned,
        fs::read(directory.0.join("evidence/mcp.png")).unwrap()
    );
    assert_eq!(
        response["result"]["structuredContent"]["result"]["image"]["sha256"],
        format!("{:x}", Sha256::digest(returned))
    );
    assert_eq!(fs::read(source_path).unwrap(), before);
    input["output"] = "../escape.png".into();
    assert_eq!(
        call(&directory, "chiyoda_render_evidence", &input)["error"]["code"],
        "path"
    );
    input["output"] = "evidence/bad.png".into();
    input["request"]["step"] = 999_999.into();
    assert_eq!(
        call(&directory, "chiyoda_render_evidence", &input)["ok"],
        false
    );
    assert!(!directory.0.join("evidence/bad.png").exists());
}

fn campaign_config(directory: &Directory) -> Value {
    let baseline = read(&directory.0.join("scenario.json"));
    json!({"campaign_version":"1","name":"Bounded campaign","screening":{"id":"screen","source":{"kind":"authored","scenario":baseline}},"stress_cases":[{"id":"stress","source":{"kind":"authored","scenario":baseline},"population_multiplier":2}],"candidates":[{"id":"wider","changes":[{"kind":"set_exit_width","id":"out","width_m":3.0}]},{"id":"forbidden","changes":[{"kind":"set_group_count","id":"person","count":4_294_967_295u64}]}],"constraints":{"allowed_edits":[{"kind":"set_exit_width","targets":["out"]}],"bounds":[{"field":"exit_width","target":"out","min":1.,"max":4.}]},"objectives":[{"metric":"completed_fraction","direction":"maximize"}],"promotion":{"max_candidates":1,"require_improvement":false}})
}
#[test]
fn campaign_tools_archive_controlled_trials_images_and_verify_after_moving_without_sources() {
    let d = Directory::new();
    write(&d.0.join("campaign.json"), &campaign_config(&d));
    let p = ok(
        &d,
        "chiyoda_campaign_plan",
        &json!({"config":"campaign.json"}),
    );
    assert_eq!(p["runnable"], true);
    assert_eq!(p["reserved_trials"], 3);
    let inspection = ok(
        &d,
        "chiyoda_campaign_plan",
        &json!({"config":"campaign.json","case":"stress"}),
    );
    assert_eq!(inspection["plan_hash"], p["plan_hash"]);
    assert!(inspection["case"]["scenario"].is_object());
    let result = ok(
        &d,
        "chiyoda_campaign_run",
        &json!({"config":"campaign.json","expected_plan_hash":p["plan_hash"],"output":"result"}),
    );
    assert_eq!(result["executed_trials"], 2);
    assert_eq!(result["rejected_trials"], 1);
    assert_eq!(result["promoted"], json!(["wider"]));
    assert!(d.0.join("result/report.md").exists());
    let images = read(&d.0.join("result/evidence-index.json"));
    assert_eq!(images["images"].as_array().unwrap().len(), 4);
    assert!(
        images["images"]
            .as_array()
            .unwrap()
            .iter()
            .all(|i| i["receipt"].is_object())
    );
    fs::rename(d.0.join("result"), d.0.join("archived")).unwrap();
    fs::remove_file(d.0.join("scenario.json")).unwrap();
    fs::remove_file(d.0.join("campaign.json")).unwrap();
    let q = ok(
        &d,
        "chiyoda_campaign_query",
        &json!({"campaign":"archived","view":"trials","limit":1}),
    );
    assert_eq!(q["total"], 3);
    assert_eq!(q["next_offset"], 1);
    assert!(
        q["items"][0]["experiment"]
            .as_str()
            .unwrap()
            .starts_with("archived/trials/")
    );
    let verified = ok(
        &d,
        "chiyoda_campaign_verify",
        &json!({"campaign":"archived","replay":true}),
    );
    assert_eq!(verified["verified"], true);
    assert_eq!(verified["images"]["successful"], 4);
    assert_eq!(verified["images"]["pixels_rederived"], true);
    let evidence = ok(
        &d,
        "chiyoda_campaign_query",
        &json!({"campaign":"archived","view":"evidence","limit":2}),
    );
    assert_eq!(evidence["total"], 4);
    assert_eq!(
        evidence["items"][0]["value"]["receipt"]["image"]["artifact"],
        "evidence/000.png"
    );
    let report_path = d.0.join("archived/report.json");
    let report = read(&report_path);
    let mut false_report = report.clone();
    false_report["promoted"] = json!([]);
    false_report["report_hash"] = "".into();
    false_report["report_hash"] = chiyoda_core::bundle::canonical_hash(&false_report).into();
    write(&report_path, &false_report);
    assert_eq!(
        call(
            &d,
            "chiyoda_campaign_verify",
            &json!({"campaign":"archived"})
        )["error"]["code"],
        "integrity"
    );
    write(&report_path, &report);
    fs::write(d.0.join("archived/evidence/000.png"), b"not a PNG").unwrap();
    assert_eq!(
        call(
            &d,
            "chiyoda_campaign_verify",
            &json!({"campaign":"archived"})
        )["error"]["code"],
        "integrity"
    );
}
#[test]
fn campaign_invalid_budgets_stale_plans_and_workspace_escapes_never_create_outputs() {
    let d = Directory::new();
    let mut config = campaign_config(&d);
    write(&d.0.join("campaign.json"), &config);
    let p = ok(
        &d,
        "chiyoda_campaign_plan",
        &json!({"config":"campaign.json"}),
    );
    config["name"] = "Changed".into();
    write(&d.0.join("campaign.json"), &config);
    assert_eq!(
        call(
            &d,
            "chiyoda_campaign_run",
            &json!({"config":"campaign.json","expected_plan_hash":p["plan_hash"],"output":"bad"})
        )["error"]["code"],
        "stale_plan"
    );
    assert!(!d.0.join("bad").exists());
    config["options"] = json!({"budget":{"max_trials":1}});
    write(&d.0.join("campaign.json"), &config);
    let p = ok(
        &d,
        "chiyoda_campaign_plan",
        &json!({"config":"campaign.json"}),
    );
    assert_eq!(p["runnable"], false);
    assert_eq!(
        call(
            &d,
            "chiyoda_campaign_run",
            &json!({"config":"campaign.json","expected_plan_hash":p["plan_hash"],"output":"bad"})
        )["error"]["code"],
        "invalid_plan"
    );
    assert!(!d.0.join("bad").exists());
    assert_eq!(
        call(
            &d,
            "chiyoda_campaign_plan",
            &json!({"config":"../outside.json"})
        )["error"]["code"],
        "path"
    );
}

#[test]
fn mcp_campaign_loop_returns_the_same_annotated_png_as_its_archived_receipt() {
    use base64::Engine as _;
    let d = Directory::new();
    let mut config = campaign_config(&d);
    config["options"] = json!({"budget":{"max_evidence_images":1}});
    write(&d.0.join("campaign.json"), &config);
    let mut rpc = Rpc::new(&d);
    rpc.initialize();
    let p = rpc.tool("chiyoda_campaign_plan", &json!({"config":"campaign.json"}));
    let response=rpc.request(&json!({"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"chiyoda_campaign_run","arguments":{"config":"campaign.json","expected_plan_hash":p["plan_hash"],"output":"result"}}}));
    assert_eq!(response["result"]["isError"], false, "{response}");
    let content = response["result"]["content"].as_array().unwrap();
    let image = content.iter().find(|c| c["type"] == "image").unwrap();
    let png = base64::engine::general_purpose::STANDARD
        .decode(image["data"].as_str().unwrap())
        .unwrap();
    assert_eq!(png, fs::read(d.0.join("result/evidence/000.png")).unwrap());
    assert_eq!(
        rpc.tool(
            "chiyoda_campaign_verify",
            &json!({"campaign":"result","replay":true})
        )["verified"],
        true
    );
    let response=rpc.request(&json!({"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"chiyoda_campaign_query","arguments":{"campaign":"result","view":"evidence","limit":1}}}));
    assert!(
        response["result"]["content"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["type"] == "image")
    );
    // A resealed malformed receipt must return an integrity error, not panic
    // while the MCP response attaches the image.
    let path = d.0.join("result/evidence-index.json");
    let mut malformed = read(&path);
    malformed["images"][0]["receipt"]["image"] = Value::Null;
    malformed["index_hash"] = "".into();
    malformed["index_hash"] = chiyoda_core::bundle::canonical_hash(&malformed).into();
    write(&path, &malformed);
    let response = rpc.request(&json!({
        "jsonrpc":"2.0","id":4,"method":"tools/call",
        "params":{"name":"chiyoda_campaign_query",
                  "arguments":{"campaign":"result","view":"evidence"}}
    }));
    assert_eq!(response["result"]["isError"], true);
    assert_eq!(
        response["result"]["structuredContent"]["error"]["code"],
        "integrity"
    );
    assert_eq!(
        rpc.tool("chiyoda_capabilities", &json!({}))["campaign_version"],
        "1"
    );
}
