//! Contextual person/resource evidence, opened only for a selection.
use super::{BORDER, INK, MOVING, MUTED, Options, PAPER, WAITING, canvas::Canvas};

fn wrapped(c: &mut Canvas, at: (f32, f32), text: &str, width: f32, max_lines: usize, color: u32) {
    let mut lines = vec![String::new()];
    for word in text.split_whitespace() {
        let last = lines.last_mut().unwrap();
        let next = if last.is_empty() {
            word.to_owned()
        } else {
            format!("{last} {word}")
        };
        if c.text_width(&next, 12) > width && !last.is_empty() {
            lines.push(word.to_owned());
        } else {
            *last = next;
        }
    }
    for (i, line) in lines.iter().take(max_lines).enumerate() {
        let line = if i + 1 == max_lines && lines.len() > max_lines {
            format!("{line} …")
        } else {
            line.clone()
        };
        #[allow(clippy::cast_precision_loss)]
        let y = at.1 + i as f32 * 18.0;
        c.text((at.0, y), &line, 12, color, width);
    }
}

#[allow(clippy::too_many_lines)] // fixed inspector sections clearly separate frame state from whole-run evidence
pub(super) fn draw(
    c: &mut Canvas,
    options: Options<'_>,
    inspection: &crate::inspection::Inspection,
) {
    c.rect([916.0, 0.0, 284.0, 704.0], PAPER);
    c.line((915.5, 16.0), (915.5, 688.0), BORDER, 1.0);
    let label = if inspection.person.is_some() {
        "PERSON"
    } else {
        "RESOURCE"
    };
    c.text((940.0, 32.0), label, 10, MUTED, 236.0);
    c.text((940.0, 57.0), inspection.selection.id(), 17, INK, 236.0);
    if let Some(person) = &inspection.person {
        c.text(
            (940.0, 81.0),
            &format!("{:?}", person.snapshot.state),
            13,
            MOVING,
            236.0,
        );
        c.text(
            (940.0, 103.0),
            &format!("On {}", person.snapshot.surface),
            12,
            MUTED,
            236.0,
        );
        c.text(
            (940.0, 125.0),
            &format!("Authored goal: {}", person.authored_destination),
            12,
            INK,
            236.0,
        );
        c.line((940.0, 142.0), (1176.0, 142.0), BORDER, 1.0);
        c.text((940.0, 165.0), "WHOLE-RUN EVIDENCE", 10, MUTED, 236.0);
        if let Some(totals) = &person.whole_run {
            for (i, text) in [
                format!("Walked {:.2} m", totals.walking_distance_m),
                format!("Moving {:.2} s", totals.time.moving_s),
                format!("Service waits {:.2} s", totals.time.service_wait_s()),
                format!("Authored pauses {:.2} s", totals.time.waypoint_pause_s),
                format!("Low progress: {} spans", totals.low_progress_total),
            ]
            .iter()
            .enumerate()
            {
                #[allow(clippy::cast_precision_loss)]
                let y = 189.0 + i as f32 * 21.0;
                c.text((940.0, y), text, 12, INK, 236.0);
            }
            if let Some(span) = totals.low_progress.last() {
                c.text(
                    (940.0, 292.0),
                    &format!(
                        "Retained span {:.2}–{:.2} s",
                        span.start_time_s, span.end_time_s
                    ),
                    11,
                    MUTED,
                    236.0,
                );
            }
        } else if let crate::inspection::Availability::Unavailable { reason } =
            &inspection.diagnostics
        {
            wrapped(c, (940.0, 189.0), reason, 236.0, 5, MUTED);
        }
    }
    if let Some(resource) = &inspection.resource {
        c.text(
            (940.0, 82.0),
            "Queue-entry history through frame",
            12,
            MUTED,
            236.0,
        );
        c.text(
            (940.0, 107.0),
            &format!(
                "{} {} · {} {}",
                resource.queue_entries_through_frame,
                if resource.queue_entries_through_frame == 1 {
                    "entry"
                } else {
                    "entries"
                },
                resource.entrants_total,
                if resource.entrants_total == 1 {
                    "person"
                } else {
                    "people"
                },
            ),
            14,
            INK,
            236.0,
        );
        c.line((940.0, 142.0), (1176.0, 142.0), BORDER, 1.0);
        c.text((940.0, 165.0), "WHOLE-RUN QUEUE AUDIT", 10, MUTED, 236.0);
        if let Some(audit) = &resource.whole_run_queue_audit {
            c.text(
                (940.0, 191.0),
                &format!(
                    "Peak waiting: {} {}",
                    audit.peak_waiting_agents,
                    if audit.peak_waiting_agents == 1 {
                        "person"
                    } else {
                        "people"
                    }
                ),
                13,
                INK,
                236.0,
            );
            c.text(
                (940.0, 215.0),
                &format!("Wait: {:.2} agent-s", audit.cumulative_wait_agent_seconds),
                13,
                INK,
                236.0,
            );
            c.text(
                (940.0, 239.0),
                &format!("Ever queued: {}", audit.ever_queued_agents),
                12,
                INK,
                236.0,
            );
        } else {
            wrapped(
                c,
                (940.0, 191.0),
                "No per-resource queue audit is recorded for this resource.",
                236.0,
                3,
                MUTED,
            );
        }
        c.text(
            (940.0, 270.0),
            &if resource.entrants_total > resource.entrants_through_frame.len() {
                format!(
                    "Rings: {} / {} historical entrants",
                    resource.entrants_through_frame.len(),
                    resource.entrants_total
                )
            } else {
                "Rings mark historical entrants.".to_owned()
            },
            11,
            WAITING,
            236.0,
        );
    }
    c.line((940.0, 306.0), (1176.0, 306.0), BORDER, 1.0);
    c.text(
        (940.0, 328.0),
        "EVENTS THROUGH THIS FRAME",
        10,
        MUTED,
        236.0,
    );
    let mut displayed: Vec<&chiyoda_core::bundle::RunEvent> = vec![];
    if let Some(route) = inspection
        .person
        .as_ref()
        .and_then(|p| p.latest_route_event.as_ref())
    {
        displayed.push(route);
    }
    if let Some(change) = inspection
        .resource
        .as_ref()
        .and_then(|r| r.latest_availability_event.as_ref())
    {
        displayed.push(change);
    }

    for event in &inspection.events {
        if !displayed.contains(&&event.event) {
            displayed.push(&event.event);
        }
        if displayed.len() >= 2 {
            break;
        }
    }
    let event_limit = if options
        .watch_status
        .is_some_and(crate::watch_status_is_error)
    {
        1
    } else {
        2
    };
    for (i, event) in displayed.into_iter().take(event_limit).enumerate() {
        #[allow(clippy::cast_precision_loss)]
        let y = 354.0 + i as f32 * 47.0;
        c.text(
            (940.0, y),
            &format!("{:.2} s · {}", event.time_s, event.kind),
            11,
            INK,
            236.0,
        );
        c.text((940.0, y + 18.0), &event.detail, 11, MUTED, 236.0);
    }
    if inspection.events.is_empty() {
        c.text(
            (940.0, 354.0),
            "No recorded events through frame.",
            12,
            MUTED,
            236.0,
        );
    }
}
