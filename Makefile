JAR := target/shed-2.0.0.jar
DEMO_ANIMATIONS := reading typing insertion deletion rewrite interrupt search navigate target checking passed failed conflict file-create file-modify file-rename file-delete terminal
DEMO_TARGETS := $(addprefix demo-,$(DEMO_ANIMATIONS))
LOOP ?= 0
ANIMATIONS ?=
ARGS ?=
PREFIX ?= $(HOME)/.local
BINDIR ?= $(PREFIX)/bin
LIBDIR ?= $(PREFIX)/lib/shed
LAUNCHER := scripts/shed
TOOLCHAIN := scripts/resolve-toolchain.sh
SOURCES := $(shell find src/main assets -type f 2>/dev/null)
RUN_GOALS := $(if $(filter run,$(MAKECMDGOALS)),$(filter-out run,$(MAKECMDGOALS)),)
RUN_ARGS := $(strip $(ARGS) $(RUN_GOALS))

.PHONY: all build run demo demo-list $(DEMO_TARGETS) profile-startup test check clean install uninstall help $(RUN_GOALS)

all: build

build: $(JAR)

$(JAR): pom.xml $(SOURCES)
	@java_home=$$($(TOOLCHAIN) home) || exit $$?; \
	 maven=$$($(TOOLCHAIN) maven) || exit $$?; \
	 JAVA_HOME="$$java_home" "$$maven" -B -q -DskipTests package

run: $(JAR)
	@$(LAUNCHER) $(RUN_ARGS)

demo $(DEMO_TARGETS): $(JAR)
	@java_home=$$($(TOOLCHAIN) home) || exit $$?; \
	 output="$$(pwd -P)/target/agent-$@-$$(date +%Y%m%d-%H%M%S)-$$$$"; \
	 printf 'Starting the disposable live agent demo: changes, navigation, checks and conflicts. Watch the Shed window.\nResults: %s\n' "$$output"; \
	 python3 scripts/test-agent-desktop.py --jdk "$$java_home" --jar "$(abspath $(JAR))" \
	   --output "$$output" --git-fixture --visual-only --animation-demo $(if $(filter demo,$@),$(if $(ANIMATIONS),--animation $(ANIMATIONS),),--animation $(@:demo-%=%)) $(if $(filter 1,$(LOOP)),--loop,)

demo-list:
	@python3 scripts/test-agent-desktop.py --list-animations

profile-startup: $(JAR)
	@SHED_STARTUP_PROFILE=1 $(LAUNCHER) $(ARGS)

install: $(JAR) $(LAUNCHER) $(TOOLCHAIN)
	install -d "$(DESTDIR)$(BINDIR)" "$(DESTDIR)$(LIBDIR)"
	install -m 755 $(LAUNCHER) "$(DESTDIR)$(BINDIR)/shed"
	install -m 755 $(TOOLCHAIN) "$(DESTDIR)$(LIBDIR)/resolve-toolchain.sh"
	install -m 644 $(JAR) "$(DESTDIR)$(LIBDIR)/$(notdir $(JAR))"

uninstall:
	rm -f "$(DESTDIR)$(BINDIR)/shed" "$(DESTDIR)$(LIBDIR)/resolve-toolchain.sh" "$(DESTDIR)$(LIBDIR)/$(notdir $(JAR))"
	rmdir --ignore-fail-on-non-empty "$(DESTDIR)$(LIBDIR)" "$(DESTDIR)$(BINDIR)" 2>/dev/null || true

test:
	@java_home=$$($(TOOLCHAIN) home) || exit $$?; \
	 maven=$$($(TOOLCHAIN) maven) || exit $$?; \
	 JAVA_HOME="$$java_home" "$$maven" -B -q -Djava.awt.headless=true test

check: test build

clean:
	@java_home=$$($(TOOLCHAIN) home) || exit $$?; \
	 maven=$$($(TOOLCHAIN) maven) || exit $$?; \
	 JAVA_HOME="$$java_home" "$$maven" -B -q clean

help:
	@printf '%s\n' 'make build              package Shed without tests'
	@printf '%s\n' 'make run [path ...]     rebuild if needed, then launch files or folders (make run . opens this folder)'
	@printf '%s\n' 'make demo               show the complete agent animation tour'
	@printf '%s\n' 'make demo-list          list individual animation demos'
	@printf '%s\n' 'make demo-reading LOOP=1 replay continuously in one window; close the window to stop'
	@printf '%s\n' 'make demo-<name>        run one animation (for example, make demo-deletion)'
	@printf '%s\n' 'make profile-startup    print local startup phase timings, then keep Shed open'
	@printf '%s\n' 'make install            install the shed launcher and JAR under ~/.local'
	@printf '%s\n' 'make uninstall          remove the locally installed shed launcher and JAR'
	@printf '%s\n' 'make test               run the headless test suite'
	@printf '%s\n' 'make check              test, then package'
	@printf '%s\n' 'make clean              remove Maven build output'
