# xThink - the five targets every repo on this machine has.
# setup: clone -> running on every connected phone, one command.

setup:
	scripts/dev.sh model
	scripts/dev.sh all

dev:
	./gradlew installDebug
	adb shell am start -n in.arasan.xthink/.MainActivity

test:
	./gradlew test

lint:
	./gradlew :app:lintDebug

deploy:
	@test -n "$(TAG)" && test -n "$(MSG)" || { echo "usage: make deploy TAG=v1.0 MSG='what shipped'"; exit 2; }
	scripts/dev.sh ship "$(TAG)" "$(MSG)"

.PHONY: setup dev test lint deploy
