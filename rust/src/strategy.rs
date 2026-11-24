//! One strategy as its editor shows it, and the check an edit passes before it is sent.
//!
//! The core owns a strategy's values and the schema that lays them out: which fields its kind
//! has, in which sections, and what control each one is. A field the core left out of the
//! strategy holds the schema's default — the core does not send values equal to it.

use std::collections::BTreeMap;

use moon_core::feed::{
    field_text_is_valid, SchemaField, SchemaFieldUi, StrategyEditPhase, StrategyEditRow,
    StrategyRow, StrategySchemaModel,
};
use serde::Serialize;

use crate::api::{ApiError, ApiResult, Uid};

#[derive(Serialize)]
pub struct StrategyView {
    pub core: u64,
    pub id: Uid,
    pub name: String,
    pub kind: String,
    pub folder: String,
    pub checked: bool,
    pub short: bool,
    /// An edit of this strategy is on its way: `pending`, `timed_out`, or `null` when none is.
    pub edit: Option<&'static str>,
    /// False while the core has not sent its schema: the fields are then listed as the strategy
    /// carries them, read-only.
    pub editable: bool,
    pub sections: Vec<SectionView>,
}

#[derive(Serialize)]
pub struct SectionView {
    pub title: String,
    pub fields: Vec<FieldView>,
}

#[derive(Serialize)]
pub struct FieldView {
    pub name: String,
    /// `checkbox`, `list`, `color` or `text`.
    pub control: &'static str,
    /// The schema's type name (`Double`, `Int32`, `Bool`, `String`, ...).
    pub type_name: String,
    /// What the core holds now.
    pub value: String,
    /// What an edit still on its way asks for, when it differs from `value`.
    pub pending: Option<String>,
    /// The choices of a `list` control; the current value is always among them.
    pub options: Vec<String>,
}

/// Lay one strategy out by the schema of its kind.
pub fn view(
    core: u64,
    row: &StrategyRow,
    schema: Option<&StrategySchemaModel>,
    edit: Option<&StrategyEditRow>,
) -> StrategyView {
    let held: BTreeMap<&str, &str> = row
        .fields
        .iter()
        .map(|(name, value)| (name.as_str(), value.as_str()))
        .collect();
    let wanted: BTreeMap<&str, &str> = edit
        .map(|edit| {
            edit.fields
                .iter()
                .map(|(name, value)| (name.as_str(), value.as_str()))
                .collect()
        })
        .unwrap_or_default();
    let kind = schema.and_then(|schema| schema.kinds.iter().find(|k| k.ordinal == row.kind_ordinal));

    let sections = match kind {
        Some(kind) => kind
            .sections
            .iter()
            .map(|section| SectionView {
                title: section.title.clone(),
                fields: section
                    .fields
                    .iter()
                    .map(|field| {
                        let value = held
                            .get(field.name.as_str())
                            .map(|v| (*v).to_string())
                            .unwrap_or_else(|| default_of(field));
                        let pending = wanted
                            .get(field.name.as_str())
                            .filter(|want| !same_value(want, &value))
                            .map(|want| (*want).to_string());
                        let control = control_of(field);
                        let mut options = if control == "list" {
                            field.picklist.clone()
                        } else {
                            Vec::new()
                        };
                        // A stored value the list does not name stays selectable, so opening
                        // the list cannot silently replace it.
                        if control == "list"
                            && !value.is_empty()
                            && !options.iter().any(|o| o.eq_ignore_ascii_case(&value))
                        {
                            options.push(value.clone());
                        }
                        FieldView {
                            name: field.name.clone(),
                            control,
                            type_name: field.type_name.clone(),
                            value,
                            pending,
                            options,
                        }
                    })
                    .collect(),
            })
            .filter(|section: &SectionView| !section.fields.is_empty())
            .collect(),
        // No schema yet: show what the strategy carries, nothing to edit against.
        None => vec![SectionView {
            title: String::new(),
            fields: row
                .fields
                .iter()
                .map(|(name, value)| FieldView {
                    name: name.clone(),
                    control: "text",
                    type_name: String::new(),
                    value: value.clone(),
                    pending: None,
                    options: Vec::new(),
                })
                .collect(),
        }],
    };

    StrategyView {
        core,
        id: Uid(row.id),
        name: row.name.clone(),
        kind: row.kind.clone(),
        folder: row.folder_path.clone(),
        checked: row.checked,
        short: row.is_short,
        edit: edit.map(|edit| match edit.phase {
            StrategyEditPhase::Pending => "pending",
            StrategyEditPhase::TimedOut => "timed_out",
        }),
        editable: kind.is_some(),
        sections,
    }
}

/// Check `changes` against the schema of the strategy's kind and drop the ones that change
/// nothing. An unknown field or a value its type cannot hold refuses the WHOLE edit: sending the
/// rest would apply half of what the user confirmed.
pub fn checked_changes(
    row: &StrategyRow,
    schema: Option<&StrategySchemaModel>,
    changes: &BTreeMap<String, String>,
) -> ApiResult<Vec<(String, String)>> {
    let kind = schema
        .and_then(|schema| schema.kinds.iter().find(|k| k.ordinal == row.kind_ordinal))
        .ok_or_else(|| {
            ApiError::new("unavailable", "the core has not sent its strategy schema yet")
        })?;
    let mut out = Vec::new();
    for (name, text) in changes {
        let field = kind
            .sections
            .iter()
            .flat_map(|section| section.fields.iter())
            .find(|field| field.name == *name)
            .ok_or_else(|| {
                ApiError::new("bad_field", format!("{name}: no such field in this strategy"))
            })?;
        let text = text.trim();
        let valid = match control_of(field) {
            // A checkbox has two states; anything else is not one of them.
            "checkbox" => matches!(
                text.to_ascii_lowercase().as_str(),
                "yes" | "no" | "true" | "false"
            ),
            _ => field_text_is_valid(&field.type_name, text),
        };
        if !valid {
            return Err(ApiError::new(
                "bad_value",
                format!("{name}: {text:?} is not a {} value", field.type_name),
            ));
        }
        let current = row
            .fields
            .iter()
            .find(|(held, _)| held == name)
            .map(|(_, value)| value.clone())
            .unwrap_or_else(|| default_of(field));
        if !same_value(text, &current) {
            out.push((name.clone(), text.to_string()));
        }
    }
    Ok(out)
}

fn control_of(field: &SchemaField) -> &'static str {
    match field.ui {
        SchemaFieldUi::Checkbox => "checkbox",
        SchemaFieldUi::Color => "color",
        SchemaFieldUi::Combo if !field.picklist.is_empty() => "list",
        _ => "text",
    }
}

/// What a field holds when the core sent no value for it.
fn default_of(field: &SchemaField) -> String {
    match &field.default {
        Some(default) => default.clone(),
        None if field.ui == SchemaFieldUi::Checkbox => "No".to_string(),
        None => String::new(),
    }
}

/// Whether two texts name one value: `1` and `1.0`, `Yes` and `true`, any letter case.
fn same_value(a: &str, b: &str) -> bool {
    let (a, b) = (a.trim(), b.trim());
    if a.eq_ignore_ascii_case(b) {
        return true;
    }
    let number = |s: &str| s.replace(',', ".").parse::<f64>().ok();
    if let (Some(x), Some(y)) = (number(a), number(b)) {
        return (x - y).abs() <= 1e-9 * x.abs().max(y.abs()).max(1.0);
    }
    let flag = |s: &str| match s.to_ascii_lowercase().as_str() {
        "yes" | "true" | "on" => Some(true),
        "no" | "false" | "off" | "" => Some(false),
        _ => None,
    };
    matches!((flag(a), flag(b)), (Some(x), Some(y)) if x == y)
}

#[cfg(test)]
mod tests {
    use super::*;
    use moon_core::feed::{SchemaKind, SchemaSection};

    fn field(name: &str, type_name: &str, ui: SchemaFieldUi, default: Option<&str>) -> SchemaField {
        SchemaField {
            name: name.to_string(),
            type_name: type_name.to_string(),
            ui,
            picklist: Vec::new(),
            default: default.map(str::to_string),
        }
    }

    fn schema() -> StrategySchemaModel {
        let mut side = field("OrderSide", "String", SchemaFieldUi::Combo, Some("Long"));
        side.picklist = vec!["Long".to_string(), "Short".to_string()];
        StrategySchemaModel {
            kinds: vec![SchemaKind {
                ordinal: 6,
                name: "MoonShot".to_string(),
                sections: vec![
                    SchemaSection {
                        title: "Main".to_string(),
                        fields: vec![
                            field("OrderSize", "Double", SchemaFieldUi::Edit, Some("100")),
                            field("SellPrice", "Double", SchemaFieldUi::Edit, Some("1.5")),
                            field("UseStopLoss", "Bool", SchemaFieldUi::Checkbox, None),
                            side,
                        ],
                    },
                    SchemaSection {
                        title: "Filters".to_string(),
                        fields: vec![field("MaxOrders", "Int32", SchemaFieldUi::Edit, Some("0"))],
                    },
                ],
            }],
        }
    }

    fn row() -> StrategyRow {
        StrategyRow {
            id: 42,
            name: "LONG 2% ETH".to_string(),
            kind: "MoonShot".to_string(),
            kind_ordinal: 6,
            folder_path: "ETH".to_string(),
            checked: true,
            is_short: false,
            // SellPrice and MaxOrders equal their defaults, so the core did not send them.
            fields: vec![
                ("OrderSize".to_string(), "250".to_string()),
                ("UseStopLoss".to_string(), "Yes".to_string()),
                ("OrderSide".to_string(), "Hedge".to_string()),
            ],
        }
    }

    fn changes(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
        pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect()
    }

    #[test]
    fn view_fills_defaults_and_keeps_an_unlisted_choice() {
        let schema = schema();
        let view = view(7, &row(), Some(&schema), None);
        assert!(view.editable);
        assert_eq!(view.sections.len(), 2);
        let main = &view.sections[0].fields;
        assert_eq!(main[0].value, "250");
        assert_eq!(main[1].value, "1.5", "absent field shows the schema default");
        assert_eq!(main[2].control, "checkbox");
        assert_eq!(main[2].value, "Yes");
        assert_eq!(main[3].control, "list");
        assert_eq!(main[3].options, ["Long", "Short", "Hedge"]);
        assert_eq!(view.sections[1].fields[0].value, "0");
    }

    #[test]
    fn view_marks_what_a_pending_edit_changes() {
        let schema = schema();
        let edit = StrategyEditRow {
            id: 42,
            phase: StrategyEditPhase::Pending,
            submitted_at_ms: 0,
            fields: vec![
                ("OrderSize".to_string(), "300".to_string()),
                ("UseStopLoss".to_string(), "Yes".to_string()),
            ],
        };
        let view = view(7, &row(), Some(&schema), Some(&edit));
        assert_eq!(view.edit, Some("pending"));
        let main = &view.sections[0].fields;
        assert_eq!(main[0].pending.as_deref(), Some("300"));
        assert_eq!(main[2].pending, None, "an unchanged field is not pending");
    }

    #[test]
    fn view_without_a_schema_is_read_only() {
        let view = view(7, &row(), None, None);
        assert!(!view.editable);
        assert_eq!(view.sections[0].fields.len(), 3);
    }

    #[test]
    fn changes_are_checked_and_no_ops_dropped() {
        let schema = schema();
        let out = checked_changes(
            &row(),
            Some(&schema),
            &changes(&[
                ("OrderSize", "250.0"),
                ("SellPrice", "2,5"),
                ("UseStopLoss", "No"),
                ("MaxOrders", "0"),
            ]),
        )
        .unwrap();
        assert_eq!(
            out,
            [
                ("SellPrice".to_string(), "2,5".to_string()),
                ("UseStopLoss".to_string(), "No".to_string()),
            ]
        );
    }

    #[test]
    fn a_bad_value_or_field_refuses_the_whole_edit() {
        let schema = schema();
        let bad_number = checked_changes(
            &row(),
            Some(&schema),
            &changes(&[("OrderSize", "300"), ("MaxOrders", "1.5")]),
        );
        assert_eq!(bad_number.unwrap_err().code, "bad_value");
        let empty = checked_changes(&row(), Some(&schema), &changes(&[("OrderSize", " ")]));
        assert_eq!(empty.unwrap_err().code, "bad_value");
        let flag = checked_changes(&row(), Some(&schema), &changes(&[("UseStopLoss", "maybe")]));
        assert_eq!(flag.unwrap_err().code, "bad_value");
        let unknown = checked_changes(&row(), Some(&schema), &changes(&[("Nope", "1")]));
        assert_eq!(unknown.unwrap_err().code, "bad_field");
        let no_schema = checked_changes(&row(), None, &changes(&[("OrderSize", "1")]));
        assert_eq!(no_schema.unwrap_err().code, "unavailable");
    }
}
