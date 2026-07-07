; Parse regex syntax within regex literals

((regex_literal) @injection.content
 (#set! injection.language "regex"))

([
  (comment)
  (multiline_comment)
] @injection.content
  (#set! injection.language "comment"))

(call_expression
  (simple_identifier) @injection.language
  (call_suffix
    (value_arguments
      (value_argument
        value: (line_string_literal
          text: (line_str_text) @injection.content)))))
