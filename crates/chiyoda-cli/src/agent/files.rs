use super::{ToolError, ToolResult};
use chiyoda_core::experiment::{
    ArmArtifacts, ExperimentExecution, ExperimentReport, ExperimentRequest,
};
use serde::{Serialize, de::DeserializeOwned};
use serde_json::json;
use std::fmt::Write as _;
use std::{
    fs,
    io::Read,
    path::{Component, Path, PathBuf},
};

const MAX_ARTIFACT_BYTES: usize = 256 * 1024 * 1024;

pub(super) struct Workspace {
    root: PathBuf,
}

impl Workspace {
    pub(super) fn new(root: &Path) -> Result<Self, ToolError> {
        let root = root
            .canonicalize()
            .map_err(|error| ToolError::new("workspace", error.to_string()))?;
        if !root.is_dir() {
            return Err(ToolError::new(
                "workspace",
                "workspace must be an existing directory",
            ));
        }
        Ok(Self { root })
    }

    pub(super) fn path(&self, relative: &str) -> Result<PathBuf, ToolError> {
        if relative.is_empty() {
            return Err(ToolError::new(
                "path",
                "paths must be nonempty and relative to the workspace",
            ));
        }
        let mut path = self.root.clone();
        for component in Path::new(relative).components() {
            match component {
                Component::Normal(name) => path.push(name),
                Component::CurDir => {}
                _ => {
                    return Err(ToolError::new(
                        "path",
                        "absolute paths and parent traversal are unsupported",
                    ));
                }
            }
            if path.symlink_metadata().is_ok() {
                path = path
                    .canonicalize()
                    .map_err(|error| ToolError::new("path", error.to_string()))?;
                if !path.starts_with(&self.root) {
                    return Err(ToolError::new(
                        "path",
                        "path resolves outside the workspace",
                    ));
                }
            }
        }
        Ok(path)
    }

    pub(super) fn new_directory(&self, relative: &str) -> Result<PathBuf, ToolError> {
        let path = self.path(relative)?;
        if path.exists() {
            return Err(ToolError::new(
                "destination_exists",
                "output directory must not already exist",
            ));
        }
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(ToolError::from)?;
        }
        fs::create_dir(&path).map_err(ToolError::from)?;
        Ok(path)
    }
}

pub(super) fn read_bytes(path: &Path, maximum: usize) -> Result<Vec<u8>, ToolError> {
    let file = fs::File::open(path).map_err(ToolError::from)?;
    if !file.metadata().map_err(ToolError::from)?.is_file() {
        return Err(ToolError::new("input", "input must be a regular file"));
    }
    let mut bytes = vec![];
    file.take(u64::try_from(maximum + 1).expect("bounded"))
        .read_to_end(&mut bytes)
        .map_err(ToolError::from)?;
    if bytes.len() > maximum {
        return Err(ToolError::new(
            "resource_limit",
            format!("{} exceeds the {maximum}-byte input limit", path.display()),
        ));
    }
    Ok(bytes)
}

pub(super) fn read<T: DeserializeOwned>(path: &Path) -> Result<T, ToolError> {
    serde_json::from_slice(&read_bytes(path, MAX_ARTIFACT_BYTES)?)
        .map_err(|error| ToolError::new("input", error.to_string()))
}

pub(super) fn write<T: Serialize>(path: &Path, value: &T) -> Result<(), ToolError> {
    super::super::write_json(path, value)
        .map_err(|error| ToolError::new("artifact_io", error.to_string()))
}

impl From<std::io::Error> for ToolError {
    fn from(error: std::io::Error) -> Self {
        Self::new("artifact_io", error.to_string())
    }
}

pub(super) fn save(
    root: &Path,
    request: &ExperimentRequest,
    execution: &ExperimentExecution,
) -> Result<(), ToolError> {
    write(&root.join("request.json"), request)?;
    for artifact in &execution.artifacts {
        let directory = root.join("arms").join(&artifact.id);
        fs::create_dir_all(&directory).map_err(ToolError::from)?;
        write(&directory.join("scenario.json"), &artifact.scenario)?;
        if let Some(bundle) = &artifact.bundle {
            write(&directory.join("run.json"), bundle)?;
        }
        if let Some(report) = &artifact.journeys {
            super::super::journeys::save(
                &directory,
                &chiyoda_core::journeys::JourneyAnalysis {
                    report: report.clone(),
                    isolated_runs: vec![],
                },
            )
            .map_err(|error| ToolError::new("artifact_io", error.to_string()))?;
        }
    }
    let mut markdown = String::from(
        "# Experiment\n\n| Arm | Completed / people | Clearance (s) | Service wait (agent-s) | Checks passed |\n|---|---:|---:|---:|---|\n",
    );
    for arm in &execution.report.arms {
        let metrics = arm.evaluation.metrics.as_ref();
        let target = if arm.journeys.is_some() {
            "journeys.md"
        } else {
            "scenario.json"
        };
        let _ = writeln!(
            markdown,
            "| [{}](arms/{}/{target}) | {} / {} | {} | {} | {} |",
            arm.id,
            arm.id,
            metrics.map_or_else(
                || "unavailable".into(),
                |metrics| metrics.evacuated_agents.to_string()
            ),
            metrics.map_or_else(
                || "unavailable".into(),
                |metrics| metrics.total_agents.to_string()
            ),
            metrics
                .and_then(|metrics| metrics.clearance_time_s)
                .map_or_else(|| "unavailable".into(), |time| format!("{time:.3}")),
            arm.journeys.as_ref().map_or_else(
                || "unavailable".into(),
                |journeys| format!("{:.3}", journeys.service_wait_agent_s)
            ),
            arm.evaluation.passed
        );
    }
    markdown.push_str("\nEach candidate starts from the same baseline. Changes, population controls, missing metrics, individual differences and evidence pointers are in [report.json](report.json). Low-progress spans overlap moving exposure. Completed-only elapsed differences have their own pair counts. Results describe the recorded simulation assumptions.\n");
    fs::write(root.join("report.md"), markdown).map_err(ToolError::from)?;
    // Publish the report last. A directory without it is visibly incomplete.
    write(&root.join("report.json"), &execution.report)
}

pub(super) fn load_report(
    workspace: &Workspace,
    experiment: &str,
) -> Result<ExperimentReport, ToolError> {
    let report: ExperimentReport = read(&workspace.path(&format!("{experiment}/report.json"))?)?;
    report.check_hash()?;
    // Arm IDs are also path components. Validate independently of report hashes.
    for arm in &report.arms {
        if arm.id.is_empty()
            || arm.id.len() > 64
            || !arm
                .id
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
        {
            return Err(ToolError::new(
                "integrity",
                "invalid archived arm identifier",
            ));
        }
    }
    Ok(report)
}

pub(super) fn verify(workspace: &Workspace, experiment: &str, replay: bool) -> ToolResult {
    let report = load_report(workspace, experiment)?;
    let request: ExperimentRequest = read(&workspace.path(&format!("{experiment}/request.json"))?)?;
    let mut artifacts = vec![];
    for arm in &report.arms {
        let root = format!("{experiment}/arms/{}", arm.id);
        artifacts.push(ArmArtifacts {
            id: arm.id.clone(),
            scenario: read(&workspace.path(&format!("{root}/scenario.json"))?)?,
            bundle: if arm.evaluation.bundle_hash.is_some() {
                Some(read(&workspace.path(&format!("{root}/run.json"))?)?)
            } else {
                None
            },
            journeys: if arm.journeys.is_some() {
                Some(read(&workspace.path(&format!("{root}/journeys.json"))?)?)
            } else {
                None
            },
        });
    }
    report.verify(&request, &artifacts, replay)?;
    Ok(
        json!({"experiment_version": report.experiment_version,"report_hash":report.report_hash,"verified":true,"verification":if replay {"evidence_and_reconstruction"} else {"rederived_evidence"},"arms":report.arms.len()}),
    )
}
