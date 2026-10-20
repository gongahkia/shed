use chiyoda_core::{decode_scenario, encode_scenario, model::AgentKnowledge, validate};
use serde_json::json;

fn scenario() -> chiyoda_core::Scenario {
    decode_scenario(include_str!("fixtures/json-free-walk.json")).unwrap()
}

#[test]
fn knowledge_is_explicit_strict_and_retains_absent_versus_empty_familiarity() {
    let mut scenario = scenario();
    let original = encode_scenario(&scenario).unwrap();
    assert!(!original.contains("knowledge"));
    for known_exits in [None, Some(vec![]), Some(vec!["out".into()])] {
        scenario.agents[0].knowledge = Some(AgentKnowledge {
            observation_radius_m: 0.0,
            known_exits,
        });
        let encoded = encode_scenario(&scenario).unwrap();
        assert_eq!(decode_scenario(&encoded).unwrap(), scenario);
    }
    let mut input: serde_json::Value = serde_json::from_str(&original).unwrap();
    input["scenario"]["agents"][0]["knowledge"] =
        json!({"observation_radius_m": 2.0, "observaton_radius_m": 3.0});
    assert!(decode_scenario(&input.to_string()).is_err());
}

#[test]
fn invalid_knowledge_ranges_and_familiarity_are_rejected() {
    for range in [-1.0, f64::NAN, f64::INFINITY] {
        let mut scenario = scenario();
        scenario.agents[0].knowledge = Some(AgentKnowledge {
            observation_radius_m: range,
            known_exits: None,
        });
        assert!(
            validate(&scenario)
                .unwrap_err()
                .iter()
                .any(|error| { error.path == "agents[0].knowledge.observation_radius_m" })
        );
    }
    for exits in [vec!["missing".into()], vec!["out".into(), "out".into()]] {
        let mut scenario = scenario();
        scenario.agents[0].knowledge = Some(AgentKnowledge {
            observation_radius_m: 2.0,
            known_exits: Some(exits),
        });
        assert!(validate(&scenario).is_err());
    }
}
