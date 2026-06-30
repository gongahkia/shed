all: dev-build

dev-build:
	swift build -c release && bench/scripts/make_app.sh&& open Itsy.app
