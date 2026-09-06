MVN ?= mvn
JAVA ?= java
JAR := target/shed-2.0.0.jar
ARGS ?=
SOURCES := $(shell find src/main assets -type f 2>/dev/null)

.PHONY: all build run test check clean help

all: build

build: $(JAR)

$(JAR): pom.xml $(SOURCES)
	$(MVN) -B -q -DskipTests package

run: $(JAR)
	$(JAVA) -jar $(JAR) $(ARGS)

test:
	$(MVN) -B -q -Djava.awt.headless=true test

check: test build

clean:
	$(MVN) -B -q clean

help:
	@printf '%s\n' 'make build              package Shed without tests'
	@printf '%s\n' 'make run [ARGS=<file>]  rebuild if needed, then launch Shed'
	@printf '%s\n' 'make test               run the headless test suite'
	@printf '%s\n' 'make check              test, then package'
	@printf '%s\n' 'make clean              remove Maven build output'
