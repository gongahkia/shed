use super::*;
use std::cmp::Ordering;
fn compare_values(a: &[ObjectiveValue], b: &[ObjectiveValue]) -> Ordering {
    for (a, b) in a.iter().zip(b) {
        let order = b.benefit.unwrap().total_cmp(&a.benefit.unwrap());
        if order != Ordering::Equal {
            return order;
        }
    }
    Ordering::Equal
}
pub(super) fn classification(values: &[ObjectiveValue], objectives: &[Objective]) -> i8 {
    for (v, o) in values.iter().zip(objectives) {
        if let Some(b) = v.benefit {
            if b > o.tolerance {
                return 1;
            }
            if b < -o.tolerance {
                return -1;
            }
        }
    }
    0
}
pub(super) fn screen<'a>(trials: &'a [TrialResult]) -> Vec<&'a TrialResult> {
    let mut ranked: Vec<_> = trials
        .iter()
        .filter(|t| t.phase == Phase::Screening && t.eligible)
        .collect();
    ranked.sort_by(|a, b| {
        compare_values(&a.objective_values, &b.objective_values).then(a.candidate.cmp(&b.candidate))
    });
    ranked
}
pub(super) fn promoted(request: &CampaignRequest, trials: &[TrialResult]) -> Vec<String> {
    screen(trials)
        .into_iter()
        .filter(|t| {
            !request.promotion.require_improvement
                || classification(&t.objective_values, &request.objectives) > 0
        })
        .take(request.promotion.max_candidates as usize)
        .map(|t| t.candidate.clone())
        .collect()
}
pub(super) fn candidates(
    request: &CampaignRequest,
    trials: &[TrialResult],
    promoted: &[String],
) -> Vec<CandidateResult> {
    let screening = screen(trials);
    let mut candidates = vec![];
    for candidate in &request.candidates {
        let cases: Vec<_> = trials
            .iter()
            .filter(|t| t.candidate == candidate.id)
            .collect();
        let selected = promoted.contains(&candidate.id);
        let eligible = cases.iter().filter(|t| t.eligible).count();
        let expected = 1 + if selected {
            request.stress_cases.len()
        } else {
            0
        };
        let screening_rank = screening
            .iter()
            .position(|t| t.candidate == candidate.id)
            .map(|i| i + 1);
        let status = if screening_rank.is_none() {
            "screening_excluded"
        } else if !selected {
            "not_promoted"
        } else if request.stress_cases.is_empty() {
            "screening_only"
        } else if eligible < expected {
            "stress_incomplete"
        } else if cases
            .iter()
            .filter(|t| t.phase == Phase::Stress)
            .any(|t| classification(&t.objective_values, &request.objectives) < 0)
        {
            "stress_regressed"
        } else {
            "stress_no_regression"
        };
        let mut objectives = vec![];
        for (i, o) in request.objectives.iter().enumerate() {
            let values: Vec<_> = cases
                .iter()
                .filter(|t| t.eligible)
                .filter_map(|t| t.objective_values.get(i)?.benefit)
                .collect();
            let n = values.len();
            #[allow(clippy::cast_precision_loss)]
            let mean = if n == 0 {
                None
            } else {
                Some(values.iter().sum::<f64>() / n as f64)
            };
            objectives.push(ObjectiveSummary {
                metric: o.metric,
                paired_cases: n,
                mean_benefit: mean,
                worst_benefit: values.iter().copied().min_by(f64::total_cmp),
                wins: values.iter().filter(|v| **v > o.tolerance).count(),
                losses: values.iter().filter(|v| **v < -o.tolerance).count(),
                ties: values.iter().filter(|v| v.abs() <= o.tolerance).count(),
            });
        }
        let mut reasons: Vec<_> = cases
            .iter()
            .flat_map(|t| {
                t.reasons
                    .iter()
                    .map(|i| format!("{}: {}: {}", t.case, i.code, i.message))
            })
            .collect();
        if !selected && screening_rank.is_some() {
            reasons.push("not selected by screening objective order / improvement requirement / promotion cap".into());
        }
        candidates.push(CandidateResult {
            candidate: candidate.id.clone(),
            promoted: selected,
            screening_rank,
            final_rank: None,
            status: status.into(),
            expected_cases: expected,
            executed_cases: cases
                .iter()
                .filter(|t| t.status == TrialStatus::Executed)
                .count(),
            eligible_cases: eligible,
            objectives,
            exclusion_reasons: reasons,
        });
    }
    let mut final_order: Vec<_> = candidates
        .iter()
        .enumerate()
        .filter(|(_, c)| {
            c.promoted && c.eligible_cases == c.expected_cases && !request.stress_cases.is_empty()
        })
        .map(|(i, _)| i)
        .collect();
    final_order.sort_by(|a, b| {
        for (a, b) in candidates[*a]
            .objectives
            .iter()
            .zip(&candidates[*b].objectives)
        {
            let cmp = b.mean_benefit.unwrap().total_cmp(&a.mean_benefit.unwrap());
            if cmp != Ordering::Equal {
                return cmp;
            }
        }
        candidates[*a].candidate.cmp(&candidates[*b].candidate)
    });
    for (rank, index) in final_order.into_iter().enumerate() {
        candidates[index].final_rank = Some(rank + 1);
    }
    candidates
}
pub(super) fn evidence(
    request: &CampaignRequest,
    trials: &[TrialResult],
    candidates: &[CandidateResult],
) -> Vec<EvidenceMoment> {
    let mut order: Vec<_> = candidates.iter().collect();
    order.sort_by_key(|c| {
        (
            c.final_rank.unwrap_or(usize::MAX),
            c.screening_rank.unwrap_or(usize::MAX),
            &c.candidate,
        )
    });
    let mut moments = vec![];
    for c in order {
        let mut cases: Vec<_> = trials
            .iter()
            .filter(|t| t.candidate == c.candidate && t.status == TrialStatus::Executed)
            .collect();
        cases.sort_by(|a, b| {
            a.objective_values
                .first()
                .and_then(|v| v.benefit)
                .unwrap_or(f64::NEG_INFINITY)
                .total_cmp(
                    &b.objective_values
                        .first()
                        .and_then(|v| v.benefit)
                        .unwrap_or(f64::NEG_INFINITY),
                )
                .then(a.case.cmp(&b.case))
        });
        for case in cases {
            for moment in &case.moments {
                if moments.len() >= request.options.budget.max_evidence_images as usize {
                    return moments;
                }
                moments.push(moment.clone());
            }
        }
    }
    moments
}
