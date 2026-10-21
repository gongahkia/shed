//! Machine interfaces share one backend; neither transport decides outcomes.

mod files;
mod mcp;
mod query;
mod replay;
mod tools;

use anyhow::Result;
use clap::{Args, Subcommand};
use serde_json::{Value, json};
use std::{
    io::{self, Read},
    path::PathBuf,
};

pub(super) const API_VERSION: &str = "1";
pub(super) const MAX_MESSAGE_BYTES: usize = 16 * 1024 * 1024;

#[derive(Debug, Args)]
pub(super) struct AgentArgs {
    #[command(subcommand)]
    command: AgentCommand,
}

#[derive(Debug, Subcommand)]
enum AgentCommand {
    /// Discover tools and their generated JSON input schemas.
    Tools,
    /// Invoke an agent tool once; stdout is a single JSON result envelope.
    Call {
        tool: String,
        /// JSON arguments file, or `-` for stdin.
        #[arg(long)]
        input: PathBuf,
        #[arg(long, default_value = ".")]
        workspace: PathBuf,
    },
    /// Serve MCP 2025-11-25 tools over newline-delimited stdio JSON-RPC.
    Serve {
        #[arg(long, default_value = ".")]
        workspace: PathBuf,
    },
}

#[derive(Debug, serde::Serialize)]
pub(super) struct ToolError {
    pub code: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub details: Option<Value>,
}

impl ToolError {
    pub(super) fn new(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            details: None,
        }
    }
}

impl From<chiyoda_core::experiment::ExperimentError> for ToolError {
    fn from(error: chiyoda_core::experiment::ExperimentError) -> Self {
        Self::new(&error.code, error.message)
    }
}

pub(super) type ToolResult = std::result::Result<Value, ToolError>;

pub(super) fn execute(args: &AgentArgs) -> Result<()> {
    match &args.command {
        AgentCommand::Tools => println!(
            "{}",
            serde_json::to_string_pretty(
                &json!({"api_version":API_VERSION,"tools":tools::catalog()})
            )?
        ),
        AgentCommand::Serve { workspace } => {
            let workspace = files::Workspace::new(workspace)
                .map_err(|error| anyhow::anyhow!("{}: {}", error.code, error.message))?;
            mcp::serve(&workspace, io::stdin().lock(), io::stdout().lock())?;
        }
        AgentCommand::Call {
            tool,
            input,
            workspace,
        } => {
            let result = (|| {
                let workspace = files::Workspace::new(workspace)?;
                let bytes = if input.as_os_str() == "-" {
                    let mut bytes = vec![];
                    io::stdin()
                        .take(u64::try_from(MAX_MESSAGE_BYTES + 1).expect("bounded"))
                        .read_to_end(&mut bytes)
                        .map_err(|error| ToolError::new("input", error.to_string()))?;
                    bytes
                } else {
                    files::read_bytes(input, MAX_MESSAGE_BYTES)?
                };
                if bytes.len() > MAX_MESSAGE_BYTES {
                    return Err(ToolError::new(
                        "resource_limit",
                        "tool arguments exceed 16 MiB",
                    ));
                }
                let arguments = serde_json::from_slice(&bytes)
                    .map_err(|error| ToolError::new("arguments", error.to_string()))?;
                tools::call(&workspace, tool, arguments)
            })();
            let (envelope, success) = match result {
                Ok(result) => (
                    json!({"api_version":API_VERSION,"ok":true,"result":result}),
                    true,
                ),
                Err(error) => (
                    json!({"api_version":API_VERSION,"ok":false,"error":error}),
                    false,
                ),
            };
            println!("{}", serde_json::to_string(&envelope)?);
            if !success {
                std::process::exit(1);
            }
        }
    }
    Ok(())
}
