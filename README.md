### The Linked Art Validator (lav)

##SUMMARY

#Lav is a validator for the Linked Art (LA) 1.0 data model.  

    * written in Java and requires a JVM >= 15
    * comes with a bash script and batch script executable for use on either Windows or linux/unix
    * can be used as a client at the command line or over the network as a service
    * the lav client returns 0 on success of all json-ld input and 1 if any inputs fail
    * validations that produce recommendations but no violations are still considered to be successful


##INSTALLATION

    1. Ensure Java is installed and has been added to the command line search path
    2. Confirm the Java version by executing "java --version" on the command line (requires Java 15 or later)

    3. CURRENT: Download the project zip file from GitHub ( https://github.com/linked-art/shacl-validator/archive/refs/heads/main.zip )
    3. FUTURE : Download the latest lav release package from GitHub ( https://github.com/linked.art/shacl-validator/releases )

    4. Unzip the release file to an installation directory of your choosing 

    5. CURRENT: Ensure the latest Apache Maven is installed and verify by running "mvn --version"
    5. CURRENT: in the <install dir>/source/ folder, run "mvn clean install" which will copy lav-0.1.jar to the bin folder


    6. Navigate to <install dir>/bin and execute lav -h to ensure you receive command line help

       Note: if you're on Linux, you might have to use ./lav if the current working directory is not already in your path


##GETTING STARTED 

Lav can be used in a variety of ways depending on your needs, so let's get familiar with the tool with a few short examples.

For all examples, change to the <install dir>/bin folder 

# simple Validation of Valid JSON File [note: exit code is 0]
lav -i ../tests/valid/basic_valid_object_0.json

# multiple json files under a folder [note: exit code is 1]
lav -i ../tests/invalid

# from STDIN (this example is for Linux - see below for Windows) 
cat ../tests/warning/warning_object_0.json | lav 

# from STDIN (Windows)
type ..\tests\warning\warning_object_0.json | lav 

# from a URL
lav -i https://linked.art/example/object/0.json


##LAV AS A WEB SERVICE

# Lav can be started as a web service (uses default service URL http://localhost:52525) and it will accept POST requests of JSON-LD for validation
lav -s 

# Lav can be bound to a specific ip/hostname and port as needed
lav -s -u http://127.0.0.1:50000

# and lav can connect to its own server which makes it run a little faster when validating a bunch of data files 
lav -u http://localhost:52525 -i ../tests/invalid

# the server is also compatible with command line tools like curl naturally
curl -X POST -d "@../tests/valid/basic_valid_object_0.json" http://localhost:52525

# and the server can be stopped
curl -k -u http://localhost:52525


## OTHER OPTIONS

# set the baseline URL for differentiating between "internal" and "external" data entities
lav -b ".*mymuseum\.org.*"

# also validate input data against the linked art JSON Schema files prior to attempting SHACL shape validation
lav -j 

# start the server with a secret that will also be required when shutting down the server 
lav -s -p mySecret

# and stop the server with that secret
lav -k -p mySecret

# instead of the default linked art ontology, load this model instead
lav --model=../ontology/myAlternateOntology.ttl

# instead of the default terms, load terms from here instead (can also be a directory which will be walked for TTL files)
lav --terms=../terms/myAlternateTerms.ttl

# instead of the default SHACL shapes, load shapes from here instead (can also be a directory which will be walked for TTL files)
lav --shapes=../shapes/myAlternateShapes.ttl


## OUTPUT

Lav outputs detailed validation results to STDOUT when operating as a client.

As a web service, lav responds with HTTP 422 UNPROCESSABLE ENTITY for Violations and 200 OK for success and warnings/recommendations.

# lav can also operating (mostly) quietly to suppress considerable output
lav -q 

# when there are validation errors, an RDF representation of the data being validated is provided by default to aid in diagnostics, but this can be disabled with
lav -g false

# there is a log4j.properties file in <install dir>/conf that can be edited and applied to both the client and server's logging to further control their output



##A FEW MORE NOTES

Many of the command line options apply to server and client modes, but some do not.  For example, a client connecting to a web service doesn't need to load the ontology so --model is pointless when -u is present but -s is not. 

There is a robust JUnit test that also tracks and reports on test coverage for all shape constraints found in the SHACL shape files. If you are developing SHACL shapes, please be sure to run the JUnit tests before preparing a pull request.

The project contains a maven build configuration so it should be relatively straightforward to import into your favorite Java IDE and get involved with contributing.  Please post general questions through the linked art mailing list or to the #api channel of the Linked Art Slack.  If there are missing features or a bug with the validator, please open a GitHub issue.


## NEXT STEPS

Currently, only art object entities enjoy SHACL shape coverage.  !!!! WE NEED MORE SHAPES !!!!.  If you're new to SHACL, please spend some time studying the established patterns in the existing shape files as they will make it a lot easier to come up-to-speed with SHACL.

The additional shapes needing coverage can be found at the bottom of the linked art API specs page: https://linked.art/api/1.0/protocol/

We also need Javadoc notation to be applied.






