# Shortcuts. Every target is a plain command you can also run yourself (see README.md).
JAR := target/api-test-platform.jar
SPEC ?= specs/your-api.yaml
ENV ?= qa
TAGS ?= @smoke

.PHONY: build unit new-spec new-env doctor serve generate services status features test report clean

build:            ## build the runnable jar
	mvn -q -DskipTests package
unit:             ## platform unit tests (no network)
	mvn -q test -Punit
new-spec:         ## onboard any spec in one go (make new-spec SPEC=path/to/api.yaml)
	./scripts/new-spec.sh $(SPEC) $(ENV)
new-env:          ## add an environment (make new-env ENV=sit)
	./scripts/new-env.sh $(ENV)
doctor: build     ## check config, secrets, proxy, company CA, reachability (make doctor ENV=sit)
	java -jar $(JAR) doctor --env $(ENV)
services: build   ## spec -> <Tag>Api endpoint classes + <Tag>Steps step definitions
	java -jar $(JAR) services --spec $(SPEC) && mvn -q -DskipTests package
serve: build      ## approval UI on :8080 (needs PLATFORM_USERS)
	java -jar $(JAR) serve
generate: build   ## spec -> JSON test cases (make generate SPEC=specs/x.yaml)
	java -jar $(JAR) generate --spec $(SPEC)
status: build
	java -jar $(JAR) status
features: build   ## approved cases -> feature files
	java -jar $(JAR) features
test: build       ## run tests + RCA + report (make test ENV=qa TAGS=@smoke)
	./scripts/run-tests.sh $(ENV) "$(TAGS)"
report: build     ## rebuild RCA + report from the last run
	java -jar $(JAR) post-run
clean:
	mvn -q clean && rm -rf reports/*.html reports/*.js reports/*.css
