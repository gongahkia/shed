import { Action, ActionPanel, Icon, List, Toast, getPreferenceValues, showToast } from "@raycast/api";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

type Preferences = {
  ollyctlPath?: string;
};

type OllyCommand = {
  id: string;
  title: string;
  subtitle: string;
  icon: Icon;
  argv: string[];
};

const execFileAsync = promisify(execFile);

const commands: OllyCommand[] = [
  { id: "state", title: "State", subtitle: "Print current state", icon: Icon.List, argv: ["state"] },
  { id: "reload", title: "Reload Config", subtitle: "Reload Config.swift", icon: Icon.RotateClockwise, argv: ["reload"] },
  { id: "focus-next", title: "Focus Next", subtitle: "Focus next window", icon: Icon.ArrowRight, argv: ["focus", "next"] },
  { id: "focus-prev", title: "Focus Previous", subtitle: "Focus previous window", icon: Icon.ArrowLeft, argv: ["focus", "previous"] },
  { id: "switch-tag-1", title: "Switch to Tag 1", subtitle: "Switch active display", icon: Icon.Circle, argv: ["switch-tag", "0"] },
  { id: "switch-tag-2", title: "Switch to Tag 2", subtitle: "Switch active display", icon: Icon.Circle, argv: ["switch-tag", "1"] },
  { id: "move-to-tag-1", title: "Move Window to Tag 1", subtitle: "Move focused window", icon: Icon.Window, argv: ["move-to-tag", "0"] },
  { id: "move-to-tag-2", title: "Move Window to Tag 2", subtitle: "Move focused window", icon: Icon.Window, argv: ["move-to-tag", "1"] },
  { id: "engine-niri", title: "Set Engine: NiriScroll", subtitle: "Use niri-scroll", icon: Icon.Sidebar, argv: ["set-engine", "niri-scroll"] },
  { id: "engine-bsp", title: "Set Engine: BSP", subtitle: "Use bsp", icon: Icon.Square3Stack3D, argv: ["set-engine", "bsp"] }
];

export default function Command() {
  return (
    <List searchBarPlaceholder="Search olly commands">
      {commands.map((command) => (
        <List.Item
          key={command.id}
          title={command.title}
          subtitle={command.subtitle}
          icon={command.icon}
          actions={
            <ActionPanel>
              <Action title="Run" icon={Icon.Play} onAction={() => run(command)} />
              <Action.CopyToClipboard title="Copy ollyctl Command" content={`ollyctl ${command.argv.join(" ")}`} />
            </ActionPanel>
          }
        />
      ))}
    </List>
  );
}

async function run(command: OllyCommand) {
  const preferences = getPreferenceValues<Preferences>();
  const ollyctl = preferences.ollyctlPath || process.env.OLLYCTL || "ollyctl";
  try {
    const result = await execFileAsync(ollyctl, command.argv);
    await showToast({
      style: Toast.Style.Success,
      title: command.title,
      message: result.stdout.trim() || "ok"
    });
  } catch (error) {
    await showToast({
      style: Toast.Style.Failure,
      title: command.title,
      message: error instanceof Error ? error.message : String(error)
    });
  }
}
