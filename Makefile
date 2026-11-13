JAR := target/shed-2.0.0.jar
ARGS ?=
PREFIX ?= $(HOME)/.local
BINDIR ?= $(PREFIX)/bin
LIBDIR ?= $(PREFIX)/lib/shed
LAUNCHER := scripts/shed
TOOLCHAIN := scripts/resolve-toolchain.sh
SOURCES := $(shell find src/main assets -type f 2>/dev/null)
RUN_GOALS := $(filter-out run,$(MAKECMDGOALS))
RUN_ARGS := $(strip $(ARGS) $(RUN_GOALS))

.PHONY: all build run profile-startup test check clean install uninstall help $(RUN_GOALS)

all: build

build: $(JAR)

$(JAR): pom.xml $(SOURCES)
	@java_home=$$($(TOOLCHAIN) home) || exit $$?; \
	 maven=$$($(TOOLCHAIN) maven) || exit $$?; \
	 JAVA_HOME="$$java_home" "$$maven" -B -q -DskipTests package

run: $(JAR)
	@$(LAUNCHER) $(RUN_ARGS)

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
	@printf '%s\n' 'make profile-startup    print local startup phase timings, then keep Shed open'
	@printf '%s\n' 'make install            install the shed launcher and JAR under ~/.local'
	@printf '%s\n' 'make uninstall          remove the locally installed shed launcher and JAR'
	@printf '%s\n' 'make test               run the headless test suite'
	@printf '%s\n' 'make check              test, then package'
	@printf '%s\n' 'make clean              remove Maven build output'
