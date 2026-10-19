//! Readable reports with metric deltas, assertion failures, and replay paths.

use super::{
    BenchmarkError, BenchmarkReport, CaseResult,
    comparison::{CaseVerdict, ComparisonReport, Metric, MetricValue, MetricVerdict, metric_value},
};
use std::{fmt::Write, path::Path};

pub fn run_markdown(report: &BenchmarkReport) -> Result<String, BenchmarkError> {
    report.verify()?;
    let mut text = format!(
        "# Benchmark: {}\n\nPassed: {}; failed: {}. Runtime: {}.\n\n",
        escaped(&report.suite.name),
        report.passed,
        report.failed,
        escaped(&report.runtime_version)
    );
    if let Some(label) = &report.label {
        writeln!(text, "Run label: {}.\n", escaped(label)).unwrap();
    }
    writeln!(text, "Report hash: {}.\n", report.report_hash).unwrap();
    text.push_str("Replay commands assume this report's directory as the working directory.\n\n| Case | Result | Evacuated | Clearance (s) | Queue wait (agent s) | Disc / swept overlap (m) | Walking alerts |\n| --- | --- | --- | --- | --- | --- | --- |\n");
    for result in &report.cases {
        let metrics = result.evaluation.metrics.as_ref();
        let value = |metric| {
            metrics
                .map(|metrics| metric_value(metrics, metric))
                .transpose()
                .map(|value| metric_text(value.flatten()))
        };
        let evacuated = metrics.map_or_else(
            || "not simulated".into(),
            |metrics| format!("{}/{}", metrics.evacuated_agents, metrics.total_agents),
        );
        writeln!(
            text,
            "| {} | {} | {evacuated} | {} | {} | {} / {} | {} |",
            escaped(&result.id),
            if result.evaluation.passed {
                "passed"
            } else {
                "FAILED"
            },
            value(Metric::ClearanceTimeS)?,
            value(Metric::QueueWaitAgentSeconds)?,
            value(Metric::DiscOverlapM)?,
            value(Metric::SweptDiscOverlapM)?,
            result.diagnosis.walking_low_progress.len()
        )
        .unwrap();
    }
    writeln!(text, "\nWalking alerts identify at most {} m of travel over {} s while recorded as moving. Queue, dwell, departure, and transit states reset the window; an alert alone is not a deadlock finding.\n", number(report.suite.diagnostics.max_travel_m), number(report.suite.diagnostics.window_s)).unwrap();
    for (index, result) in report.cases.iter().enumerate() {
        writeln!(
            text,
            "## {}\n\n{}\n",
            escaped(&result.id),
            escaped(&report.suite.cases[index].description)
        )
        .unwrap();
        case_evidence(&mut text, result, Path::new("."), index);
        for failure in &result.evaluation.failures {
            writeln!(
                text,
                "- **{}**: {}",
                escaped(&failure.check),
                escaped(&failure.detail)
            )
            .unwrap();
        }
        walking_alerts(&mut text, result);
    }
    Ok(text)
}

/// The caller supplies artifact roots: `.` for an in-run comparison, or the
/// existing run directories for an offline comparison.
#[must_use]
pub fn comparison_markdown(
    report: &ComparisonReport,
    current: &BenchmarkReport,
    baseline_root: &Path,
    current_root: &Path,
) -> String {
    let mut text = format!(
        "# Benchmark comparison\n\nRegressed: {}; incompatible: {}; improved: {}; unchanged: {}.\n\n",
        report.regressed, report.incompatible, report.improved, report.unchanged
    );
    writeln!(
        text,
        "Baseline: {} ({}). Current: {} ({}).\n",
        escaped(report.baseline_label.as_deref().unwrap_or("unlabeled")),
        escaped(&report.baseline_runtime_version),
        escaped(report.current_label.as_deref().unwrap_or("unlabeled")),
        escaped(&report.current_runtime_version)
    )
    .unwrap();
    writeln!(
        text,
        "Baseline report: {}. Current report: {}. Policy: {}.\n",
        report.baseline_report_hash, report.current_report_hash, report.policy_hash
    )
    .unwrap();
    text.push_str("Cases with regressions or incompatible inputs appear first. Allowed deterioration is absolute tolerance plus relative tolerance times the absolute baseline value. A missing clearance time remains unavailable.\n\n| Case | Result |\n| --- | --- |\n");
    let mut cases: Vec<_> = report.cases.iter().collect();
    cases.sort_by_key(|case| rank(case.verdict));
    for case in &cases {
        writeln!(text, "| {} | {:?} |", escaped(&case.id), case.verdict).unwrap();
    }
    for case in cases {
        writeln!(text, "\n## {} — {:?}\n", escaped(&case.id), case.verdict).unwrap();
        for reason in &case.reasons {
            writeln!(text, "- {}", escaped(reason)).unwrap();
        }
        if !case.metrics.is_empty() {
            text.push_str("\n| Metric | Baseline | Current | Delta | Allowed deterioration | Result |\n| --- | --- | --- | --- | --- | --- |\n");
            for metric in &case.metrics {
                writeln!(
                    text,
                    "| {} | {} | {} | {} | {} | {} |",
                    metric.metric.as_str(),
                    metric_text(metric.baseline),
                    metric_text(metric.current),
                    metric.delta.map_or_else(|| "unavailable".into(), signed),
                    metric
                        .allowed_deterioration
                        .map_or_else(|| "unavailable".into(), number),
                    verdict(metric.verdict)
                )
                .unwrap();
            }
        }
        if let Some(index) = case.baseline_index {
            artifact_links(&mut text, "Baseline", baseline_root, index);
        }
        if let Some(index) = case.current_index {
            artifact_links(&mut text, "Current", current_root, index);
            if let Some(result) = current.cases.get(index) {
                if result.evaluation.bundle_hash.is_some() {
                    replay_command(&mut text, current_root, index);
                }
                walking_alerts(&mut text, result);
            }
        }
    }
    text
}

fn case_evidence(text: &mut String, result: &CaseResult, root: &Path, index: usize) {
    let directory = root.join(format!("case-{index:04}"));
    writeln!(
        text,
        "[Source](<{}>) · [Case report](<{}>)\n",
        href(&directory.join("scenario.chy")),
        href(&directory.join("report.json"))
    )
    .unwrap();
    if result.evaluation.bundle_hash.is_some() {
        writeln!(
            text,
            "[Run bundle](<{}>)\n",
            href(&directory.join("run.json"))
        )
        .unwrap();
        replay_command(text, root, index);
    }
}

fn artifact_links(text: &mut String, label: &str, root: &Path, index: usize) {
    let directory = root.join(format!("case-{index:04}"));
    writeln!(
        text,
        "\n{label}: [source](<{}>) · [case report](<{}>).",
        href(&directory.join("scenario.chy")),
        href(&directory.join("report.json"))
    )
    .unwrap();
}

fn replay_command(text: &mut String, root: &Path, index: usize) {
    let path = root.join(format!("case-{index:04}/run.json"));
    let command = format!(
        "chiyoda-replay {} --paused",
        shell_quote(&path.to_string_lossy())
    );
    writeln!(text, "\n    {}\n", command.replace('\n', "\n    ")).unwrap();
}

fn walking_alerts(text: &mut String, result: &CaseResult) {
    if result.diagnosis.walking_low_progress.is_empty() {
        return;
    }
    text.push_str("\n| Moving agent | Surface | Time interval (s) | Steps | Minimum window travel (m) |\n| --- | --- | --- | --- | --- |\n");
    for window in &result.diagnosis.walking_low_progress {
        writeln!(
            text,
            "| {} | {} | {}–{} | {}–{} | {} |",
            escaped(&window.agent),
            escaped(&window.surface),
            number(window.start_time_s),
            number(window.end_time_s),
            window.start_step,
            window.end_step,
            number(window.minimum_window_travel_m)
        )
        .unwrap();
    }
    text.push('\n');
}

fn rank(verdict: CaseVerdict) -> u8 {
    match verdict {
        CaseVerdict::Regressed => 0,
        CaseVerdict::Incompatible => 1,
        CaseVerdict::Improved => 2,
        CaseVerdict::Unchanged => 3,
    }
}
fn verdict(verdict: MetricVerdict) -> &'static str {
    match verdict {
        MetricVerdict::Regressed => "REGRESSED",
        MetricVerdict::Improved => "improved",
        MetricVerdict::Unchanged => "unchanged",
        MetricVerdict::Unavailable => "unavailable",
    }
}

fn metric_text(value: Option<MetricValue>) -> String {
    value.map_or_else(
        || "unavailable".into(),
        |value| match value {
            MetricValue::Count(value) => value.to_string(),
            MetricValue::Number(value) => number(value),
        },
    )
}
fn signed(value: f64) -> String {
    if value > 0.0 {
        format!("+{}", number(value))
    } else {
        number(value)
    }
}
fn number(value: f64) -> String {
    if value != 0.0 && (value.abs() < 0.000_001 || value.abs() >= 1_000_000_000.0) {
        return format!("{value:.6e}");
    }
    format!("{value:.6}")
        .trim_end_matches('0')
        .trim_end_matches('.')
        .to_owned()
}

fn escaped(value: &str) -> String {
    let mut escaped = String::new();
    for character in value.chars() {
        match character {
            '\n' | '\r' | '\t' => escaped.push(' '),
            '&' => escaped.push_str("&amp;"),
            '<' => escaped.push_str("&lt;"),
            '>' => escaped.push_str("&gt;"),
            '`' | '[' | ']' | '*' | '_' | '#' | '|' | '\\' => {
                escaped.push('\\');
                escaped.push(character);
            }
            _ => escaped.push(character),
        }
    }
    escaped
}

fn href(path: &Path) -> String {
    let mut href = String::new();
    for byte in path.to_string_lossy().bytes() {
        if byte.is_ascii_alphanumeric() || b"/.-_~:".contains(&byte) {
            href.push(char::from(byte));
        } else {
            write!(href, "%{byte:02X}").unwrap();
        }
    }
    href
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\"'\"'"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_paths_and_replay_commands_preserve_literal_user_data() {
        assert_eq!(
            escaped("case | [link](x)\n<script>"),
            "case \\| \\[link\\](x) &lt;script&gt;"
        );
        assert_eq!(href(Path::new("space #1/a'b")), "space%20%231/a%27b");
        assert_eq!(shell_quote("a'b$(cmd)"), "'a'\"'\"'b$(cmd)'");
        assert_eq!(number(0.000_000_001), "1.000000e-9");
        assert_eq!(metric_text(None), "unavailable");
    }
}
