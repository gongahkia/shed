def make_value(value):
    stepped = value + 1  # BREAKPOINT
    watched = stepped * 2
    print(f"watched={watched}")
    return watched


result = make_value(41)
print(f"result={result}")
