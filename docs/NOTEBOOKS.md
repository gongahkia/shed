# Jupyter Notebooks

Opening a local `.ipynb` file presents Shed's native notebook surface. It presents editable Markdown and code-cell source, preserves notebook metadata and existing cell outputs, adds code/Markdown cells, and saves through the normal atomic file writer. It does not render Markdown; use a Markdown preview outside the notebook surface when that is needed.

```text
:notebook open
:notebook run
:notebook raw
```

`Run all` and `:notebook run` first save the notebook, then explicitly run:

```text
jupyter nbconvert --to notebook --execute --inplace <notebook>
```

Each cell also has **Run to here**. It saves the notebook, creates a temporary prefix notebook in the project directory, runs that prefix with a fresh local kernel, and merges its outputs back into those cells. This is useful for a sequential checkpoint, not persistent per-cell kernel state.

The command is direct argv, runs as an asynchronous job, has a ten-minute limit, and requires the local `jupyter` CLI. Notebook execution is subject to the existing project-trust decision. Shed does not download Jupyter, create kernels, or execute a notebook automatically.

The native surface displays plain-text stream/error output and `text/plain` display data. It does not execute HTML/JavaScript output, render rich image output, keep a persistent kernel, provide kernel selection/control, manage notebooks over a remote kernel protocol, or implement a general notebook extension API. Use `:notebook raw` to return to the JSON text buffer.
