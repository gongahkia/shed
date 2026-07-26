(source_file) @local.scope
(function_declaration
  name: (identifier) @local.definition)
(short_var_declaration
  left: (expression_list
    (identifier) @local.definition))
(identifier) @local.reference
