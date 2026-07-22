all: dev-build

dev-build:
	swift build -c release && bench/scripts/make_app.sh&& open Itsy.app

docs:
	scripts/gen_keymap_docs.swift

screenshots:
	scripts/capture_screenshots.sh

lsp-matrix:
	scripts/lsp_matrix.sh $(ARGS)
