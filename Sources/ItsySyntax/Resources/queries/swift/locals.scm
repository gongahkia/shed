(import_declaration
  (identifier) @local.definition.import)

(function_declaration
  name: (simple_identifier) @local.definition.function)

(parameter
  name: (simple_identifier) @local.definition)

(property_declaration
  name: (pattern
    bound_identifier: (simple_identifier) @local.definition))

(simple_identifier) @local.reference

; Scopes
[
  (statements)
  (for_statement)
  (while_statement)
  (repeat_while_statement)
  (do_statement)
  (if_statement)
  (guard_statement)
  (switch_statement)
  (property_declaration)
  (function_declaration)
  (class_declaration)
  (protocol_declaration)
] @local.scope
