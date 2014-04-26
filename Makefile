.PHONY: clean compile install debug release

all: clean compile install debug release

install-dev-deps:
	mvn install:install-file -Dfile=appthwack-1.7-SNAPSHOT-jar-with-dependencies.jar -DpomFile=appthwack-java-pom.xml

clean:
	mvn clean

compile:
	mvn compile

install: clean
	mvn install

debug: install
	mvn hpi:run

release:
	mvn release:clean release:prepare release:perform
