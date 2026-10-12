# Desktop UI and UX audit

This review covers Shed's editor, settings, command palette, completion popup, menus, and window layout. It applies the typography, surfaces, motion, icons, and performance checks from the `make-interfaces-feel-better` skill to the existing Swing UI. The editor's typing path remains free of decorative animation.

## Coverage

| Area | States checked | Result |
| --- | --- | --- |
| Typography | Normal and muted text, selected text, line numbers, wrapping at narrow widths | Derived text colors now meet a 4.5:1 target; settings descriptions wrap. |
| Surfaces | Editor, settings controls, menus, palette, completion popup | Settings and find controls follow the active theme; completion uses only the width its content needs. |
| Focus and keyboard | Plain, Vim, and Emacs profiles; settings fields; suggestion list | Menus expose common commands, folder opening works in each profile, and F6 moves focus into suggestions. |
| Loading and empty states | Settings search, live find, completion wait | Settings has an empty result state; find shows search progress and match count; completion retains its source label. |
| Motion and performance | Typing, search filtering, scrolling, suggestion paint | Settings search is debounced; the file-position cue updates without a full status refresh; suggestion paint has its own timing metric. |
| Icons | Existing menu, button, and palette action symbols | Kept text labels for the new commands; the existing controls remain identifiable without introducing a new icon style. |

## Findings and changes

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| High | Settings dialog | Rows forced horizontal overflow and saving rebuilt controls. | Rows track the viewport width, details wrap, search waits for a short pause, and refresh restores focus and scroll. | Keeps controls readable and reduces focus disruption. |
| High | Navigation | Common commands lacked an application menu; palette rows lacked shortcut cues. | File, Edit, View, Navigate, and Help menus expose commands; palette rows show shortcuts and the preview shows descriptions. | Makes actions discoverable with mouse, keyboard, and assistive technology. |
| High | Theme colors | Derived selection and muted colors could be hard to read. | Derived colors adjust toward a readable foreground; selected text and line numbers are checked across all built-in themes. | Improves legibility across themes. |
| Medium | Welcome shortcut | Open Folder was advertised but missing in Plain mode. | Ctrl/Cmd+Shift+O opens a folder in all three keymap profiles. | The advertised action now works consistently. |
| High | Find and replace | Plain mode used modal dialogs and gave no replacement count or preview. | A nonmodal bar shows live matches, the selected match, replacement preview, and Replace All count; replacement is one undo step. | Keeps the editor visible during search and lets users inspect the change first. |
| High | Completion | A wide popup reserved documentation space, had no list focus path, and had no popup latency metric. | Undocumented items use a compact popup; F6 focuses the accessible list; `completion.input-to-paint` records suggestion latency. | Reduces visual obstruction and makes latency measurable. |
| Medium | File and window position | Scrollbars were hidden by default and the window maximized on every launch. | Scrollbars are visible by default, a separate status cue shows file position, and normal bounds and maximized state are saved locally. | Restores context between launches and makes long-file position visible. |

## Choices considered

- **A permanent tab strip:** deferred because the existing buffer picker and split layout already handle file switching; a persistent file-position cue addresses the specific missing orientation signal without taking editor height.
- **Animations on popup and bar transitions:** skipped because these controls appear during typing and quick navigation, where immediate response matters more.
- **A new icon set:** skipped because the current Swing controls and labels can communicate these actions without introducing a second visual system.
- **Always showing completion documentation:** rejected because many local suggestions have no documentation and the empty pane obscured code.

## Verification

Automated checks cover all built-in theme contrast values, the folder shortcut, window geometry, live replacement and undo, menu presence, scrollbar defaults, and repeated completion popup updates. Rendered Swing captures were checked for the editor, settings, inline find, and compact completion states. The local `:perf` view now includes `completion.input-to-paint`; actual latency remains workload and machine dependent.

The full suite ran 926 tests with nine failures. All nine also fail on the original `613c75a5` revision on this machine; they concern remote or container task routing, debug setup, and `/var` versus `/private/var` paths. The original revision ran 919 tests with ten failures, including a dialog zoom assertion that assumed a wider screen than this machine has. The revised zoom check respects the existing screen-size clamp. Focused tests for the changed UI paths pass.

This is a local desktop UI. The design choices align with [Apple's macOS guidance on menus, shortcuts, and resizable windows](https://developer.apple.com/design/human-interface-guidelines/designing-for-macos/) and use [WCAG's 4.5:1 normal-text contrast threshold](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) as a legibility target.

## Verdict

The seven reviewed UI issues are addressed in the Swing app. The remaining full-suite failures reproduce on the original revision and are outside this UI work.
