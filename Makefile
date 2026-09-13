MVN ?= mvn
JAVA ?= java
JAR := target/shed-2.0.0.jar
ARGS ?=
PREFIX ?= $(HOME)/.local
BINDIR ?= $(PREFIX)/bin
LIBDIR ?= $(PREFIX)/lib/shed
LAUNCHER := scripts/shed
SOURCES := $(shell find src/main assets -type f 2>/dev/null)
RUN_GOALS := $(filter-out run,$(MAKECMDGOALS))
RUN_ARGS := $(strip $(ARGS) $(RUN_GOALS))

.PHONY: all build run test check clean install uninstall help $(RUN_GOALS)

all: build

build: $(JAR)

$(JAR): pom.xml $(SOURCES)
	$(MVN) -B -q -DskipTests package

run: $(JAR)
	$(JAVA) -jar $(JAR) $(RUN_ARGS)

install: $(JAR) $(LAUNCHER)
	install -d "$(DESTDIR)$(BINDIR)" "$(DESTDIR)$(LIBDIR)"
	install -m 755 $(LAUNCHER) "$(DESTDIR)$(BINDIR)/shed"
	install -m 644 $(JAR) "$(DESTDIR)$(LIBDIR)/$(notdir $(JAR))"

uninstall:
	rm -f "$(DESTDIR)$(BINDIR)/shed" "$(DESTDIR)$(LIBDIR)/$(notdir $(JAR))"
	rmdir --ignore-fail-on-non-empty "$(DESTDIR)$(LIBDIR)" "$(DESTDIR)$(BINDIR)" 2>/dev/null || true

test:
	$(MVN) -B -q -Djava.awt.headless=true test

check: test build

clean:
	$(MVN) -B -q clean

help:
	@printf '%s\n' 'make build              package Shed without tests'
	@printf '%s\n' 'make run [path ...]     rebuild if needed, then launch files or folders (make run . opens this folder)'
	@printf '%s\n' 'make install            install the shed launcher and JAR under ~/.local'
	@printf '%s\n' 'make uninstall          remove the locally installed shed launcher and JAR'
	@printf '%s\n' 'make test               run the headless test suite'
	@printf '%s\n' 'make check              test, then package'
	@printf '%s\n' 'make clean              remove Maven build output'
