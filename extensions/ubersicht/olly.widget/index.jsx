export const command = 'PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"; "${OLLYCTL:-ollyctl}" state --json 2>/dev/null || true';
export const refreshFrequency = 1000;

export const className = `
  left: 12px;
  top: 0;
  height: 24px;
  font: 12px -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
  color: #f5f5f7;
  pointer-events: none;

  .olly {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 24px;
    padding: 0 10px;
    background: rgba(18, 18, 20, 0.72);
    border: 1px solid rgba(255, 255, 255, 0.14);
    border-radius: 6px;
    box-sizing: border-box;
    backdrop-filter: blur(18px);
    white-space: nowrap;
  }

  .olly.offline {
    color: #ffb4a8;
  }

  .label {
    color: rgba(245, 245, 247, 0.62);
  }
`;

export function render({ output }) {
  const state = parseState(output);
  if (!state.online) {
    return h("div", { className: "olly offline" }, [
      h("span", { className: "label" }, "olly"),
      h("span", {}, state.reason)
    ]);
  }
  return h("div", { className: "olly" }, [
    h("span", { className: "label" }, "olly"),
    h("span", {}, `tag ${state.tags}`),
    h("span", {}, state.engine),
    h("span", {}, `${state.windowCount} win`),
    h("span", {}, `${state.floatingCount} float`),
    h("span", {}, state.focused)
  ]);
}

export function parseState(output) {
  const trimmed = String(output || "").trim();
  if (trimmed.length === 0) {
    return offline("offline");
  }
  try {
    const response = JSON.parse(trimmed);
    if (response.status !== "ok" || !response.result || response.result.name !== "state") {
      return offline("bad state");
    }
    return summarize(response.result.payload || {});
  } catch (error) {
    return offline("bad json");
  }
}

function summarize(payload) {
  const displays = Array.isArray(payload.displays) ? payload.displays : [];
  const windows = Array.isArray(payload.windows) ? payload.windows : [];
  const display = displays[0] || {};
  const activeTags = Array.isArray(display.activeTags) ? display.activeTags : [];
  const focusedWindow = windows.find((window) => window.windowID === payload.focusedWindowID);
  const tags = activeTags.length > 0 ? activeTags.map((tag) => Number(tag) + 1).join(",") : "-";
  return {
    online: true,
    tags,
    engine: engineFor(display, activeTags[0]),
    windowCount: windows.length,
    floatingCount: windows.filter((window) => window.isFloating).length,
    focused: focusedWindow ? titleFor(focusedWindow) : "no focus"
  };
}

function engineFor(display, activeTag) {
  const bindings = Array.isArray(display.tagEngines) ? display.tagEngines : [];
  const binding = bindings.find((item) => item.tag === activeTag);
  return binding && binding.engineID ? binding.engineID.rawValue : "floating";
}

function titleFor(window) {
  if (window.title && window.title.length > 0) {
    return window.title;
  }
  return `#${window.windowID}`;
}

function offline(reason) {
  return { online: false, reason };
}

function h(type, props, children) {
  return React.createElement(type, props, children);
}
