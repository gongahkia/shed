package shed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Creates isolated startup files for command-aware Bash, Zsh, Fish, and PowerShell terminals. */
final class TerminalShellIntegration {
    record Launch(List<String> command, Map<String, String> environment, boolean enabled) {
        Launch {
            command = List.copyOf(command == null ? List.of() : command);
            environment = Map.copyOf(environment == null ? Map.of() : environment);
        }
    }

    private TerminalShellIntegration() {
    }

    static Launch prepare(List<String> requested, ConfigManager config) throws IOException {
        if (config == null) return new Launch(requested, Map.of(), false);
        Path directory = Path.of(config.getShedDirectoryPath()).resolve("shell-integration");
        return prepare(requested, config.getTerminalShellIntegrationEnabled(), directory);
    }

    static Launch prepare(List<String> requested, boolean enabled, Path directory) throws IOException {
        List<String> command = requested == null ? List.of() : List.copyOf(requested);
        if (!enabled || command.isEmpty()) return new Launch(command, Map.of(), false);
        String shell = shellName(command.getFirst());
        if (!interactive(command, shell) || !"bash".equals(shell) && !"zsh".equals(shell) && !"fish".equals(shell)
            && !"powershell".equals(shell) && !"pwsh".equals(shell)) return new Launch(command, Map.of(), false);

        Files.createDirectories(directory);
        Path script = directory.resolve(powerShell(shell) ? "powershell.ps1" : shell + ".sh");
        AtomicFileWriter.write(script, scriptFor(shell).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("TERM_PROGRAM", "shed");
        environment.put("SHED_SHELL_INTEGRATION", "1");
        environment.put("SHED_SHELL_INTEGRATION_SCRIPT", script.toString());
        if (powerShell(shell)) {
            Path bootstrap = directory.resolve("powershell-bootstrap.ps1");
            AtomicFileWriter.write(bootstrap, powerShellBootstrap().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new Launch(List.of(command.getFirst(), "-NoProfile", "-NoExit", "-File", bootstrap.toString()), environment, true);
        }
        if ("bash".equals(shell)) {
            Path bootstrap = directory.resolve("bashrc");
            AtomicFileWriter.write(bootstrap, bashBootstrap().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new Launch(List.of(command.getFirst(), "--noprofile", "--rcfile", bootstrap.toString(), "-i"), environment, true);
        }

        if ("fish".equals(shell)) {
            Path fishDirectory = directory.resolve("fish");
            Files.createDirectories(fishDirectory);
            AtomicFileWriter.write(fishDirectory.resolve("config.fish"), fishConfigBootstrap().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            environment.put("SHED_ORIGINAL_FISH_CONFIG", originalFishConfig().toString());
            environment.put("XDG_CONFIG_HOME", directory.toString());
            return new Launch(List.of(command.getFirst(), "-i"), environment, true);
        }

        Path zshDirectory = directory.resolve("zsh");
        Files.createDirectories(zshDirectory);
        AtomicFileWriter.write(zshDirectory.resolve(".zprofile"), zshProfileBootstrap().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicFileWriter.write(zshDirectory.resolve(".zshrc"), zshRcBootstrap().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicFileWriter.write(zshDirectory.resolve(".zlogin"), zshLoginBootstrap().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        environment.put("SHED_ORIGINAL_ZDOTDIR", System.getenv().getOrDefault("ZDOTDIR", System.getProperty("user.home")));
        environment.put("ZDOTDIR", zshDirectory.toString());
        return new Launch(List.of(command.getFirst(), "-i"), environment, true);
    }

    private static boolean interactive(List<String> command, String shell) {
        if (command.size() == 1) return true;
        for (int index = 1; index < command.size(); index++) {
            String argument = command.get(index);
            if (powerShell(shell)) {
                if (!"-nologo".equalsIgnoreCase(argument) && !"-noexit".equalsIgnoreCase(argument)) return false;
            } else if (!"-l".equals(argument) && !"--login".equals(argument) && !"-i".equals(argument)) return false;
        }
        return true;
    }

    private static boolean powerShell(String shell) {
        return "powershell".equals(shell) || "pwsh".equals(shell);
    }

    private static String shellName(String value) {
        String name = basename(value);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static String basename(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return (slash < 0 ? normalized : normalized.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }

    private static String bashBootstrap() {
        return """
            # generated by Shed for this terminal only; it does not alter user dotfiles
            if [ -r \"$HOME/.bash_profile\" ]; then . \"$HOME/.bash_profile\";
            elif [ -r \"$HOME/.bash_login\" ]; then . \"$HOME/.bash_login\";
            elif [ -r \"$HOME/.profile\" ]; then . \"$HOME/.profile\"; fi
            if [ -r \"$HOME/.bashrc\" ]; then . \"$HOME/.bashrc\"; fi
            if [ -n \"${SHED_SHELL_INTEGRATION_SCRIPT:-}\" ] && [ -r \"$SHED_SHELL_INTEGRATION_SCRIPT\" ]; then . \"$SHED_SHELL_INTEGRATION_SCRIPT\"; fi
            """;
    }

    private static String zshProfileBootstrap() {
        return """
            if [ -r \"${SHED_ORIGINAL_ZDOTDIR:-$HOME}/.zprofile\" ]; then source \"${SHED_ORIGINAL_ZDOTDIR:-$HOME}/.zprofile\"; fi
            """;
    }

    private static String zshRcBootstrap() {
        return """
            if [ -r \"${SHED_ORIGINAL_ZDOTDIR:-$HOME}/.zshrc\" ]; then source \"${SHED_ORIGINAL_ZDOTDIR:-$HOME}/.zshrc\"; fi
            if [ -n \"${SHED_SHELL_INTEGRATION_SCRIPT:-}\" ] && [ -r \"$SHED_SHELL_INTEGRATION_SCRIPT\" ]; then source \"$SHED_SHELL_INTEGRATION_SCRIPT\"; fi
            """;
    }

    private static String zshLoginBootstrap() {
        return """
            if [ -r \"${SHED_ORIGINAL_ZDOTDIR:-$HOME}/.zlogin\" ]; then source \"${SHED_ORIGINAL_ZDOTDIR:-$HOME}/.zlogin\"; fi
            """;
    }

    private static Path originalFishConfig() {
        String configured = System.getenv("XDG_CONFIG_HOME");
        Path base = configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.home"), ".config") : Path.of(configured);
        return base.resolve("fish").resolve("config.fish");
    }

    private static String fishConfigBootstrap() {
        return """
            # generated by Shed for this terminal only; it does not alter user dotfiles
            if test -n "$SHED_ORIGINAL_FISH_CONFIG"; and test -r "$SHED_ORIGINAL_FISH_CONFIG"
                source "$SHED_ORIGINAL_FISH_CONFIG"
            end
            if test -n "$SHED_SHELL_INTEGRATION_SCRIPT"; and test -r "$SHED_SHELL_INTEGRATION_SCRIPT"
                source "$SHED_SHELL_INTEGRATION_SCRIPT"
            end
            """;
    }

    private static String powerShellBootstrap() {
        return """
            # generated by Shed for this terminal only; it does not alter user profiles
            $shedProfiles = @($PROFILE.CurrentUserAllHosts, $PROFILE.CurrentUserCurrentHost) | Select-Object -Unique
            foreach ($shedProfile in $shedProfiles) {
                if ($shedProfile -and (Test-Path -LiteralPath $shedProfile -PathType Leaf)) { . $shedProfile }
            }
            if ($env:SHED_SHELL_INTEGRATION_SCRIPT -and (Test-Path -LiteralPath $env:SHED_SHELL_INTEGRATION_SCRIPT -PathType Leaf)) {
                . $env:SHED_SHELL_INTEGRATION_SCRIPT
            }
            """;
    }

    private static String scriptFor(String shell) {
        if (powerShell(shell)) return """
            # generated by Shed; loaded only by its integrated terminal
            function global:_shed_emit([string]$kind, [object]$value) {
                $shedValue = if ($null -eq $value) { '' } else { [string]$value }
                $shedValue = $shedValue.Replace("`r", ' ').Replace("`n", ' ').Replace([string][char]0, ' ')
                [Console]::Out.Write("`e]1341;shed;$kind;$shedValue`a")
            }
            $global:ShedLastHistoryId = -1
            $global:ShedCommandEmitted = $false
            $global:ShedOriginalPrompt = (Get-Command prompt -CommandType Function).ScriptBlock
            function global:prompt {
                $shedSuccess = $?
                $shedExitCode = $global:LASTEXITCODE
                $shedHistory = Get-History -Count 1 -ErrorAction SilentlyContinue
                if ($shedHistory -and $shedHistory.Id -ne $global:ShedLastHistoryId) {
                    $global:ShedLastHistoryId = $shedHistory.Id
                    if ($global:ShedCommandEmitted) { $global:ShedCommandEmitted = $false }
                    else { _shed_emit 'command' $shedHistory.CommandLine }
                }
                $shedStatus = if ($shedSuccess) { 0 } elseif ($null -ne $shedExitCode -and $shedExitCode -ne 0) { $shedExitCode } else { 1 }
                _shed_emit 'finished' $shedStatus
                _shed_emit 'cwd' $ExecutionContext.SessionState.Path.CurrentLocation.Path
                & $global:ShedOriginalPrompt
            }
            if (Get-Command Set-PSReadLineKeyHandler -ErrorAction SilentlyContinue) {
                $shedEnter = Get-PSReadLineKeyHandler -Chord Enter -ErrorAction SilentlyContinue
                if ($shedEnter -and $shedEnter.Function -eq 'AcceptLine') {
                    Set-PSReadLineKeyHandler -Chord Enter -ScriptBlock {
                        param($key, $arg)
                        $shedLine = $null
                        $shedCursor = 0
                        [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$shedLine, [ref]$shedCursor)
                        if (-not [string]::IsNullOrWhiteSpace($shedLine)) {
                            _shed_emit 'command' $shedLine
                            $global:ShedCommandEmitted = $true
                        }
                        [Microsoft.PowerShell.PSConsoleReadLine]::AcceptLine()
                    }
                }
            }
            """;
        if ("fish".equals(shell)) return """
            # generated by Shed; loaded only by its integrated terminal
            function _shed_emit
                printf '\\e]1341;shed;%s;%s\\a' "$argv[1]" "$argv[2]"
            end
            function _shed_preexec --on-event fish_preexec
                _shed_emit command "$argv[1]"
            end
            function _shed_postexec --on-event fish_postexec
                _shed_emit finished "$status"
            end
            function _shed_prompt --on-event fish_prompt
                _shed_emit cwd "$PWD"
            end
            """;
        return "bash".equals(shell) ? """
            # generated by Shed; loaded only by its integrated terminal
            _shed_emit() { printf '\\033]1341;shed;%s;%s\\007' \"$1\" \"$2\"; }
            _shed_command_seen=''
            _shed_prompt_ready=0
            _shed_preexec() {
              [ \"$_shed_prompt_ready\" = 1 ] || return
              case \"$BASH_COMMAND\" in _shed_*) return;; esac
              [ \"$BASH_COMMAND\" = \"$_shed_command_seen\" ] && return
              _shed_command_seen=\"$BASH_COMMAND\"; _shed_emit command \"$BASH_COMMAND\"
            }
            _shed_precmd() { local status=$?; _shed_emit finished \"$status\"; _shed_emit cwd \"$PWD\"; _shed_command_seen=''; _shed_prompt_ready=1; }
            trap '_shed_preexec' DEBUG
            PROMPT_COMMAND=\"_shed_precmd${PROMPT_COMMAND:+;$PROMPT_COMMAND}\"
            """ : """
            # generated by Shed; loaded only by its integrated terminal
            _shed_emit() { printf '\\033]1341;shed;%s;%s\\007' \"$1\" \"$2\"; }
            _shed_preexec() { _shed_emit command \"$1\"; }
            _shed_precmd() { local exit_code=$?; _shed_emit finished \"$exit_code\"; _shed_emit cwd \"$PWD\"; }
            autoload -Uz add-zsh-hook
            add-zsh-hook preexec _shed_preexec
            add-zsh-hook precmd _shed_precmd
            """;
    }
}
