use super::{ensure_parent, read_json};
use anyhow::{Context, Result, bail};
use chiyoda_core::{RunBundle, bundle::canonical_hash, encode_scenario};
use std::{fs::OpenOptions, io::Write, path::Path};

pub(super) fn extract(bundle_path: &Path, output: &Path) -> Result<()> {
    let bundle: RunBundle = read_json(bundle_path)?;
    if !bundle.verifies_hash() || bundle.scenario_hash != canonical_hash(&bundle.scenario) {
        bail!("run bundle or embedded scenario hash does not match its content");
    }
    if bundle.scenario.check_schema().is_err() && bundle.scenario.schema_version != "0.29" {
        bail!(
            "cannot migrate scenario schema {}",
            bundle.scenario.schema_version
        );
    }
    let json = encode_scenario(&bundle.scenario.scenario)?;
    ensure_parent(output)?;
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(output)
        .with_context(|| {
            format!(
                "creating {} (file must not already exist)",
                output.display()
            )
        })?;
    file.write_all(json.as_bytes())
        .with_context(|| format!("writing {}", output.display()))?;
    println!("scenario: {}", output.display());
    Ok(())
}
