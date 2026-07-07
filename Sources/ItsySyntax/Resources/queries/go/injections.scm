(call_expression
  function: (identifier) @injection.language
  arguments: (argument_list
    [
      (raw_string_literal
        (raw_string_literal_content) @injection.content)
      (interpreted_string_literal
        (interpreted_string_literal_content) @injection.content)
    ]))
