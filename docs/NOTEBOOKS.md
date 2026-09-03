# Jupyter Notebooks

Opening a local `.ipynb` file presents Shed's native notebook surface. It renders Markdown and code cells, permits editing cell source, preserves notebook metadata and existing cell outputs, adds code/Markdown cells, and saves through the normal atomic file writer.

```text
:notebook open
:notebook run
:notebook raw
```

`Run all` and `:notebook run` first save the notebook, then explicitly run:

```text
jupyter nbconvert --to notebook --execute --inplace <notebook>
```

The command is direct argv, runs as an asynchronous job, has a ten-minute limit, and requires the local `jupyter` CLI. Notebook execution is subject to the existing project-trust decision. Shed does not download Jupyter, create kernels, or execute a notebook automatically.

The native renderer displays plain-text stream/error output and `text/plain` display data. It does not execute HTML/JavaScript output, render rich image output, provide per-cell kernel control, manage notebooks over a remote kernel protocol, or implement a general notebook extension API. Use `:notebook raw` to return to the JSON text buffer.
