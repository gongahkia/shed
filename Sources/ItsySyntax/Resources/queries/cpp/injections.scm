(raw_string_literal
  delimiter: (raw_string_delimiter) @injection.language
  (raw_string_content) @injection.content)

(call_expression
  function: (identifier) @injection.language
  arguments: (argument_list
    (string_literal
      (string_content) @injection.content)))
