# include ./Makefile.dev

###
## starsign-api: common <- core <- webservice (pom.xml is the top-level build)
## The portal lives next to this folder, in ../starsign-portal
###
MVN := mvn
API := webservice
PORTAL := ../starsign-portal
API_VERSION = $$($(MVN) help:evaluate -Dexpression=project.version -q -DforceStdout)
PORTAL_VERSION = $$(node -p "require('./$(PORTAL)/package.json').version")

build_common:
	$(MVN) -pl common install -DskipTests=true

build_core:
	$(MVN) -pl core -am install -DskipTests=true

build_api:
	. ./.env && $(MVN) install -DskipTests=true

test_core:
	. ./.env && $(MVN) -pl core -am test

test_webservice: build_core
	. ./.env && $(MVN) -pl webservice test

test_api:
	. ./.env && $(MVN) test

test_portal:
	cd $(PORTAL) && npx ng test --watch=false

clean_api:
	$(MVN) clean

build_portal_dist:
	. ./.env && cd $(PORTAL) && npm run build -- --configuration=production

build_app: build_api build_portal_dist

clean_build: clean_all build_app

clean_all: clean_api

clean_module:
	$(MVN) -f ${module} clean

###
## Images (the api image copies the jar built by build_api; the portal image builds itself)
###
build_api_image: build_api
	. ./.env && export API_IMAGE_VERSION=$(API_VERSION) && docker build -t $${API_IMAGE_NAME}:$${API_IMAGE_VERSION} . && docker tag $${API_IMAGE_NAME}:$${API_IMAGE_VERSION} $${API_IMAGE_NAME}:latest

build_portal_image:
	. ./.env && export PORTAL_IMAGE_VERSION=$(PORTAL_VERSION) && docker build -t $${PORTAL_IMAGE_NAME}:$${PORTAL_IMAGE_VERSION} $(PORTAL) && docker tag $${PORTAL_IMAGE_NAME}:$${PORTAL_IMAGE_VERSION} $${PORTAL_IMAGE_NAME}:latest

build_images: build_api_image build_portal_image

###
## tag and push the images
###
push_api_image:
	. ./.env && export API_IMAGE_VERSION=$(API_VERSION) && docker push $${API_IMAGE_NAME}:$${API_IMAGE_VERSION} && docker push $${API_IMAGE_NAME}:latest

push_portal_image:
	. ./.env && export PORTAL_IMAGE_VERSION=$(PORTAL_VERSION) && docker push $${PORTAL_IMAGE_NAME}:$${PORTAL_IMAGE_VERSION} && docker push $${PORTAL_IMAGE_NAME}:latest

push_images: push_api_image push_portal_image

###
## Versions
## make versions                         -> show current versions
## make set_api_version VERSION=1.2.0    -> starsign (all its maven modules)
## make set_portal_version VERSION=1.2.0
## make set_versions VERSION=1.2.0       -> backend + portal
###
check_version:
	@test -n "$(VERSION)" || (echo "VERSION is required, e.g. make $(MAKECMDGOALS) VERSION=1.2.0" && exit 1)

versions:
	@echo "api:    $(API_VERSION)"
	@echo "portal: $(PORTAL_VERSION)"

set_api_version: check_version
	$(MVN) versions:set -DnewVersion=$(VERSION) -DprocessAllModules=true -DgenerateBackupPoms=false

set_portal_version: check_version
	cd $(PORTAL) && npm version $(VERSION) --no-git-tag-version --allow-same-version

set_versions: set_api_version set_portal_version

###
## Run the local api and web
###
run_module_local:
	. ./.env && cd ${module} && mvn spring-boot:run

run_api_local: build_core
	. ./.env && $(MVN) -pl webservice spring-boot:run

run_portal_local:
	cd $(PORTAL) && npm start

###
## Docker compose stack (postgres, redis, webservice, portal; keycloak with the keycloak profile)
###
compose_up: build_images
	docker compose up -d

compose_up_all: build_images
	docker compose --profile keycloak up -d

compose_down:
	docker compose --profile keycloak down

compose_logs:
	docker compose logs -f ${service}

###
## Keycloak: create or update the starsign realm on KEYCLOAK_URL
###
import_realm:
	. ./.env && keycloak/import-realm.sh
