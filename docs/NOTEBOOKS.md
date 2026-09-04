# Jupyter Notebooks

Opening a local `.ipynb` file presents Shed's native notebook surface. It presents editable Markdown and code-cell source, preserves notebook metadata and existing cell outputs, adds code/Markdown cells, and saves through the normal atomic file writer. Markdown cells can toggle an in-place sanitized preview; remote images, scripts, and generated Mermaid/math assets are not loaded there.

```text
:notebook open
:notebook run [kernel]
:notebook kernels
:notebook select
:notebook kernel [name]
:notebook console [kernel]
:notebook raw
```

`Run all` and `:notebook run [kernel]` first save the notebook, then explicitly run:

```text
jupyter nbconvert --to notebook --execute --inplace <notebook>
```

The notebook toolbar's **Choose Kernel…** button and `:notebook select` explicitly run local kernel discovery, then show installed kernels in a picker. Choosing one stages the standard `metadata.kernelspec` fields in the native notebook view; **Save** or **Run all** persists them. `:notebook kernel <name>` is the direct, persistent alternative for a known simple kernelspec name. Shed preserves unrelated notebook metadata.

`Run all`, **Run to here**, and `:notebook run` use the persisted selected kernelspec when no argument is supplied. Passing an installed kernelspec name to `:notebook run <kernel>` is a one-shot override and adds `--ExecutePreprocessor.kernel_name=<kernel>` to the direct command. `:notebook console` likewise uses the selected kernel unless its optional argument overrides it. A notebook with no valid selected kernelspec delegates selection to Jupyter.

`:notebook kernels` and the picker are explicit, trusted-workspace discovery actions. They run `jupyter kernelspec list --json`; `:notebook kernels` shows installed names, display names, and declared languages in a scratch buffer, while the picker lets the user choose one. Neither changes Jupyter's global configuration or creates a kernel.

Each cell also has **Run to here**. It saves the notebook, creates a temporary prefix notebook in the project directory, runs that prefix with a fresh local instance of the selected kernel, and merges its outputs back into those cells. This is useful for a sequential checkpoint, not persistent per-cell kernel state.

`:notebook console [kernel]` opens `jupyter console` in an explicit terminal at the notebook's directory. Passing a simple installed kernelspec name adds `--kernel <name>`; omitting it uses the notebook selection when valid, otherwise delegates selection to Jupyter. This gives an interactive local kernel session without embedding a terminal or kernel protocol in the notebook editor.

The command is direct argv, runs as an asynchronous job, has a ten-minute limit, and requires the local `jupyter` CLI. Notebook execution is subject to the existing project-trust decision. Shed does not download Jupyter, create kernels, or execute a notebook automatically.

The native surface displays plain-text stream/error output, `text/plain` display data, and bounded PNG/JPEG display images. It validates image format/dimensions before decoding and scales large valid images for the view. It does not execute HTML/JavaScript output, render SVG or arbitrary rich MIME output, keep a persistent kernel, provide kernel lifecycle controls (interrupt, restart, shutdown), manage notebooks over a remote kernel protocol, or implement a general notebook extension API. Use `:notebook raw` to return to the JSON text buffer.
