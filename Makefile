.PHONY: clean compile install debug

all: clean compile install debug

clean:
	mvn clean

compile:
	mvn compile

install: clean
	mvn install -Dskiptests

debug: install
	mvn hpi:run -Dskiptests
