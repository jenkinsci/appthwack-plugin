.PHONY: clean compile install debug release

all: clean compile install debug release

clean:
	mvn clean

compile:
	mvn compile

install: clean
	mvn install -Dskiptests

debug: install
	mvn hpi:run -Dskiptests

release:
	mvn release:clean release:prepare release:perform
